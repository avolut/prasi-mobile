import { readdir, mkdir, readFile, writeFile } from "node:fs/promises";
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

  // Handle package directory changes
  const oldPackagePath = `android/app/src/main/kotlin/${oldPackage.replace(/\./g, "/")}`;
  const newPackagePath = `android/app/src/main/kotlin/${newPackage.replace(/\./g, "/")}`;

  changes.directoryChanges.push({
    oldPath: oldPackagePath,
    newPath: newPackagePath,
    type: "move"
  });

  return changes;
}

/**
 * Applies the changes to the file system
 */
export async function applyChanges(changes: RenameChanges): Promise<void> {
  // First apply file changes
  for (const change of changes.fileChanges) {
    try {
      await writeFile(change.path, change.newContent, 'utf-8');
    } catch (err) {
      const error = err as Error;
      throw new Error(`Failed to modify file ${change.path}: ${error.message}`);
    }
  }

  // Then handle directory changes
  for (const change of changes.directoryChanges) {
    try {
      if (change.type === "move") {
        // Create target directory structure
        const dir = change.newPath.substring(0, change.newPath.lastIndexOf("/"));
        await mkdir(dir, { recursive: true });
        await writeFile(`${dir}/.gitkeep`, "");

        // Move files from old to new location
        const files = await readdir(change.oldPath);
        for (const file of files) {
          if (file === ".gitkeep") continue;
          const content = await readFile(`${change.oldPath}/${file}`, 'utf-8');
          await writeFile(`${change.newPath}/${file}`, content, 'utf-8');
        }
      }
    } catch (err) {
      const error = err as Error;
      throw new Error(
        `Failed to handle directory change ${change.oldPath} -> ${change.newPath}: ${error.message}`
      );
    }
  }
}

/**
 * Verifies that changes were applied correctly
 */
export async function verifyChanges(changes: RenameChanges): Promise<boolean> {
  // Verify file changes
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

  // Verify directory changes
  for (const change of changes.directoryChanges) {
    try {
      if (change.type === "move") {
        try {
          await readdir(change.newPath);
        } catch {
          return false;
        }
      }
    } catch {
      return false;
    }
  }

  return true;
}