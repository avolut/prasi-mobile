import { readdir, mkdir, readFile, writeFile, rm } from "node:fs/promises";
import type { FileChange, DirectoryChange, RenameChanges } from "./types";

/**
 * Generates changes required for renaming
 */
export async function generateChanges(
  oldPackage: string,
  newPackage: string,
  oldAppName: string,
  newAppName: string
): Promise<RenameChanges> {
  const changes: RenameChanges = {
    fileChanges: [],
    directoryChanges: []
  };

  // Handle build.gradle.kts changes
  const buildGradlePath = "android/app/build.gradle.kts";
  const buildGradleContent = await readFile(buildGradlePath, 'utf-8');
  const newBuildGradleContent = buildGradleContent
    .replace(`namespace = "${oldPackage}"`, `namespace = "${newPackage}"`)
    .replace(`applicationId = "${oldPackage}"`, `applicationId = "${newPackage}"`);
  
  changes.fileChanges.push({
    path: buildGradlePath,
    oldContent: buildGradleContent,
    newContent: newBuildGradleContent
  });

  // Handle AndroidManifest.xml changes
  const manifestPath = "android/app/src/main/AndroidManifest.xml";
  const manifestContent = await readFile(manifestPath, 'utf-8');
  const newManifestContent = manifestContent.replace(
    `android:name="${oldPackage}`,
    `android:name="${newPackage}`
  );

  changes.fileChanges.push({
    path: manifestPath,
    oldContent: manifestContent,
    newContent: newManifestContent
  });

  // Handle strings.xml changes
  const stringsPath = "android/app/src/main/res/values/strings.xml";
  const stringsContent = await readFile(stringsPath, 'utf-8');
  const newStringsContent = stringsContent.replace(
    `<string name="app_name">${oldAppName}</string>`,
    `<string name="app_name">${newAppName}</string>`
  );

  changes.fileChanges.push({
    path: stringsPath,
    oldContent: stringsContent,
    newContent: newStringsContent
  });

  // Handle settings.gradle.kts changes
  const settingsPath = "android/settings.gradle.kts";
  const settingsContent = await readFile(settingsPath, 'utf-8');
  const newSettingsContent = settingsContent.replace(
    `rootProject.name = "${oldAppName}"`,
    `rootProject.name = "${newAppName}"`
  );

  changes.fileChanges.push({
    path: settingsPath,
    oldContent: settingsContent,
    newContent: newSettingsContent
  });

  // Only copy MainActivity.kt to new package location
  const oldPath = `android/app/src/main/kotlin/${oldPackage.replace(/\./g, "/")}/MainActivity.kt`;
  const newPath = `android/app/src/main/kotlin/${newPackage.replace(/\./g, "/")}/MainActivity.kt`;

  // Read MainActivity content
  const mainActivityContent = await readFile(oldPath, 'utf-8');
  
  // Update only the package declaration in MainActivity
  const newMainActivityContent = mainActivityContent.replace(
    /package\s+[\w.]+/,
    `package ${newPackage}`
  );

  changes.fileChanges.push({
    path: newPath,
    oldContent: '',  // New file
    newContent: newMainActivityContent
  });

  // Don't create a directory change since we're not moving everything

  return changes;
}

/**
 * Applies the changes to the file system
 */
export async function applyChanges(changes: RenameChanges): Promise<void> {
  // Apply file changes
  for (const change of changes.fileChanges) {
    try {
      // Create parent directory if needed
      const dir = change.path.substring(0, change.path.lastIndexOf("/"));
      await mkdir(dir, { recursive: true });

      // Write file content
      await writeFile(change.path, change.newContent, 'utf-8');
    } catch (err) {
      const error = err as Error;
      throw new Error(`Failed to modify file ${change.path}: ${error.message}`);
    }
  }
}

/**
 * Verifies that changes were applied correctly
 */
export async function verifyChanges(changes: RenameChanges): Promise<boolean> {
  // Only verify file changes
  for (const change of changes.fileChanges) {
    try {
      const content = await readFile(change.path, 'utf-8');
      if (content !== change.newContent) {
        return false;
      }
    } catch {
      return false;
    }
  }
  return true;
}