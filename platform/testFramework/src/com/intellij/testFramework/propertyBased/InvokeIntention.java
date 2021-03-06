/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.testFramework.propertyBased;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl;
import com.intellij.util.containers.ContainerUtil;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jetCheck.Generator;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class InvokeIntention extends ActionOnFile {
  private static final Logger LOG = Logger.getInstance("#com.intellij.testFramework.propertyBased.InvokeIntention");
  private final IntentionPolicy myPolicy;

  public InvokeIntention(PsiFile file, IntentionPolicy policy) {
    super(file);
    myPolicy = policy;
  }

  @Override
  public void performCommand(@NotNull Environment env) {
    int offset = generateDocOffset(env, "Go to offset %s and run daemon");

    doInvokeIntention(offset, env);
  }

  @Nullable
  private static IntentionAction chooseIntention(@NotNull Environment env, List<IntentionAction> actions) {
    if (actions.isEmpty()) {
      env.logMessage("No intentions found");
      return null;
    }

    IntentionAction result = env.generateValue(Generator.sampledFrom(actions).noShrink(), null);
    env.logMessage("Invoke intention '" + result.getText() + "'");
    return result;
  }

  private void doInvokeIntention(int offset, Environment env) {
    Project project = getProject();
    Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, getVirtualFile(), offset), true);
    assert editor != null;

    boolean containsErrorElements = MadTestingUtil.containsErrorElements(getFile().getViewProvider());
    boolean hasErrors = !highlightErrors(project, editor).isEmpty() || containsErrorElements;

    PsiFile file = PsiUtilBase.getPsiFileInEditor(editor, getProject());
    assert file != null;
    List<IntentionAction> intentions = getAvailableIntentions(editor, file);
    // Do not reuse originally passed offset here, sometimes it's adjusted by Editor
    PsiElement currentElement = file.findElementAt(editor.getCaretModel().getOffset());
    intentions = wrapAndCheck(env, editor, currentElement, containsErrorElements, hasErrors, intentions);
    IntentionAction intention = chooseIntention(env, intentions);
    if (intention == null) return;

    String intentionString = intention.toString();

    boolean checkComments = myPolicy.checkComments(intention) && PsiTreeUtil
                                                                   .getParentOfType(file.findElementAt(offset), PsiComment.class, false) == null;
    Collection<String> comments = checkComments
                                  ? extractCommentsReformattedToSingleWhitespace(file)
                                  : Collections.emptyList();

    boolean mayBreakCode = myPolicy.mayBreakCode(intention, editor, file);
    Document changedDocument = getDocumentToBeChanged(intention);
    String textBefore = changedDocument == null ? null : changedDocument.getText();
    Long stampBefore = changedDocument == null ? null : changedDocument.getModificationStamp();

    Disposable disposable = Disposer.newDisposable();
    if (containsErrorElements) {
      Registry.get("ide.check.structural.psi.text.consistency.in.tests").setValue(false, disposable);
      Disposer.register(disposable, this::restoreAfterPotentialPsiTextInconsistency);
    }

    Runnable r = () -> CodeInsightTestFixtureImpl.invokeIntention(intention, file, editor, intention.getText());
    try {
      if (changedDocument != null) {
        MadTestingUtil.restrictChangesToDocument(changedDocument, r);
      } else {
        r.run();
      }

      if (changedDocument != null &&
          PsiDocumentManager.getInstance(project).isDocumentBlockedByPsi(changedDocument)) {
        throw new AssertionError("Document is left blocked by PSI");
      }
      if (!hasErrors && stampBefore != null && stampBefore.equals(changedDocument.getModificationStamp())) {
        String message = "No change was performed in the document";
        if (intention.startInWriteAction()) {
          message += ".\nIf it's by design that " + intentionString + " doesn't change source files, " +
                     "it should return false from 'startInWriteAction'";
        }
        throw new AssertionError(message);
      }

      PsiTestUtil.checkPsiStructureWithCommit(getFile(), PsiTestUtil::checkStubsMatchText);

      if (!mayBreakCode && !hasErrors) {
        checkNoNewErrors(project, editor, intentionString);
      }

      if (checkComments) {
        List<String> fileComments = extractCommentsReformattedToSingleWhitespace(file);
        for (String comment : comments) {
          if (!fileComments.contains(comment)) {
            throw new AssertionError("Lost comment '" + comment + "' during " + intentionString);
          }
        }
      }
    }
    catch (Throwable error) {
      LOG.debug("Error occurred, text before intention invocation:\n" + textBefore);
      env.logMessage("Error happened, the file's text before invoking printed to the debug log, search for 'text before intention invocation' there");
      throw error;
    }
    finally {
      Disposer.dispose(disposable);
    }
  }

  @NotNull
  private List<IntentionAction> wrapAndCheck(Environment env,
                                             Editor editor,
                                             PsiElement currentElement,
                                             boolean containsErrorElements,
                                             boolean hasErrors,
                                             List<IntentionAction> intentions) {
    if (currentElement == null) return intentions;
    int offset = editor.getCaretModel().getOffset();
    /*
     * When start offset of the element exactly equals to offset in the editor, we have a dubious situation
     * which we'd like to avoid: sometimes intention looks what's on the left of caret, but we add a parenthesis there and things changed.
     * E.g. "a" + <caret>"b" allows to join plus, but "a" + (<caret>"b") does not, and this looks legit as the intention reacts on plus,
     * not on literal.
     */
    List<PsiElement> elementsToWrap = ContainerUtil.filter(myPolicy.getElementsToWrap(currentElement),
                                                           e -> e.getTextRange().getStartOffset() != offset);
    if (elementsToWrap.isEmpty()) return intentions;

    Project project = getProject();
    Set<String> names = StreamEx.of(intentions).map(IntentionAction::getText).toSet();
    PsiElement elementToWrap = env.generateValue(Generator.sampledFrom(elementsToWrap).noShrink(), null);
    String text = elementToWrap.getText();
    String prefix = myPolicy.getWrapPrefix();
    String suffix = myPolicy.getWrapSuffix();
    env.logMessage("Wrap '" + StringUtil.shortenTextWithEllipsis(text.replace('\n', ' '), 50, 10) +
                   "' with '" + prefix + "..." + suffix + "' and rerun daemon");
    TextRange range = elementToWrap.getTextRange();
    PsiFile file = currentElement.getContainingFile();
    WriteCommandAction.runWriteCommandAction(project, () -> {
      getDocument().insertString(range.getEndOffset(), suffix);
      getDocument().insertString(range.getStartOffset(), prefix);
      editor.getCaretModel().moveToOffset(offset + prefix.length());
    });
    List<String> messages = new ArrayList<>();

    boolean newContainsErrorElements = MadTestingUtil.containsErrorElements(getFile().getViewProvider());
    if (newContainsErrorElements != containsErrorElements) {
      messages.add(newContainsErrorElements ? "File contains parse errors after wrapping" : "File parse errors were fixed after wrapping");
    }
    else {
      boolean newHasErrors = !highlightErrors(project, editor).isEmpty() || containsErrorElements;
      if (newHasErrors != hasErrors) {
        messages
          .add(newHasErrors ? "File contains errors after wrapping" : "File errors were fixed after wrapping");
      }
    }
    intentions = getAvailableIntentions(editor, file);
    Set<String> namesWithParentheses = StreamEx.of(intentions).map(IntentionAction::getText).toSet();
    Set<String> added = new HashSet<>(namesWithParentheses);
    added.removeAll(names);
    Set<String> removed = new HashSet<>(names);
    removed.removeAll(namesWithParentheses);
    Function<String, String> cleaner = name -> name.replace(prefix, "").replace(suffix, "");
    // Exclude pairs like "Extract if (!foo)" and "Extract if (!(foo))"
    for (Iterator<String> iterator = added.iterator(); iterator.hasNext(); ) {
      String newName = iterator.next();
      String stripped = cleaner.apply(newName);
      if (removed.removeIf(n -> cleaner.apply(n).equals(stripped))) {
        iterator.remove();
      }
    }
    if (!added.isEmpty()) {
      messages.add("Intentions added after parenthesizing:\n" + StreamEx.of(added).map("\t"::concat).joining("\n"));
    }
    if (!removed.isEmpty()) {
      messages.add("Intentions removed after parenthesizing:\n" + StreamEx.of(removed).map("\t"::concat).joining("\n"));
    }
    if (!messages.isEmpty()) {
      throw new AssertionError(String.join("\n", messages));
    }
    return intentions;
  }

  private void restoreAfterPotentialPsiTextInconsistency() {
    PushedFilePropertiesUpdater.getInstance(getProject()).filePropertiesChanged(getVirtualFile(), Conditions.alwaysTrue());
  }

  protected List<String> extractCommentsReformattedToSingleWhitespace(PsiFile file) {
    return PsiTreeUtil.findChildrenOfType(file, PsiComment.class)
      .stream()
      .filter(comment -> myPolicy.trackComment(comment)).map(comment -> comment.getText().replaceAll("[\\s*]+", " ")).collect(Collectors.toList());
  }

  private static void checkNoNewErrors(Project project, Editor editor, String intentionString) {
    List<HighlightInfo> errors = highlightErrors(project, editor);
    if (!errors.isEmpty()) {
      throw new AssertionError("New highlighting errors introduced after invoking " + intentionString +
                               "\nIf this is correct, add it to IntentionPolicy#mayBreakCode." +
                               "\nErrors found: " + StringUtil.join(errors, i -> shortInfoText(i), ","));
    }
  }

  @NotNull
  private static String shortInfoText(HighlightInfo info) {
    return "'" + info.getDescription() + "'(" + info.startOffset + "," + info.endOffset + ")";
  }

  @NotNull
  private static List<HighlightInfo> highlightErrors(Project project, Editor editor) {
    List<HighlightInfo> infos = RehighlightAllEditors.highlightEditor(editor, project);
    return ContainerUtil.filter(infos, i -> i.getSeverity() == HighlightSeverity.ERROR);
  }

  @Nullable
  private Document getDocumentToBeChanged(IntentionAction intention) {
    PsiElement changedElement = intention.getElementToMakeWritable(getFile());
    PsiFile changedFile = changedElement == null ? null : changedElement.getContainingFile();
    return changedFile == null ? null : changedFile.getViewProvider().getDocument();
  }

  private List<IntentionAction> getAvailableIntentions(Editor editor, PsiFile file) {
    List<IntentionAction> actions =
      ContainerUtil.filter(CodeInsightTestFixtureImpl.getAvailableIntentions(editor, file), myPolicy::mayInvokeIntention);
    if (actions.isEmpty()) return Collections.emptyList();

    // skip only after checking intentions for applicability, to catch possible exceptions from them
    int offset = editor.getCaretModel().getOffset();
    if (MadTestingUtil.isAfterError(file, offset) || MadTestingUtil.isAfterError(file, offset - 1)) {
      return Collections.emptyList();
    }

    return actions;
  }
}
