package com.intellij.openapi.application;

import com.intellij.ide.plugins.PluginManagerCore;

// see com.intellij.openapi.application.ConfigImportHelper.getConfigImportSettings
@SuppressWarnings("UnusedDeclaration")
public class PyCharmEduConfigImportSettings extends ConfigImportSettings {
  public PyCharmEduConfigImportSettings() {
    PluginManagerCore.disablePlugin("com.jetbrains.edu.coursecreator");
    PluginManagerCore.disablePlugin("com.jetbrains.edu.coursecreator.python");
  }

}
