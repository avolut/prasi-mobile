#!/usr/bin/env bun
import { parseArgs } from "node:util";
import type { AndroidConfig, CLIOptions } from "./types";
import { validateAndroidConfig, validateFileSystem } from "./validation";
import { generateChanges, applyChanges, verifyChanges } from "./fileOps";

async function readConfig(path: string): Promise<AndroidConfig> {
  try {
    const content = await Bun.file(path).text();
    return JSON.parse(content) as AndroidConfig;
  } catch (err) {
    const error = err as Error;
    throw new Error(`Failed to read config file: ${error.message}`);
  }
}

function parseOptions(): CLIOptions {
  const { values } = parseArgs({
    options: {
      config: {
        type: "string",
        short: "c",
        default: "config.json"
      },
      "dry-run": {
        type: "boolean",
        short: "d",
        default: false
      }
    }
  });

  return {
    config: values.config as string,
    dryRun: values["dry-run"] as boolean
  };
}

function printChanges(oldConfig: AndroidConfig, newConfig: AndroidConfig): void {
  console.log("\nProposed changes:");
  console.log("================");
  console.log(`App Name: ${oldConfig.appName} -> ${newConfig.appName}`);
  console.log(`Package Name: ${oldConfig.packageName} -> ${newConfig.packageName}`);
  console.log("\nFiles to be modified:");
  console.log("- android/app/build.gradle.kts");
  console.log("- android/app/src/main/AndroidManifest.xml");
  console.log("- android/app/src/main/res/values/strings.xml");
  console.log("- android/settings.gradle.kts");
  console.log("\nDirectories to be moved:");
  console.log(`- ${oldConfig.packageName.replace(/\./g, "/")} -> ${newConfig.packageName.replace(/\./g, "/")}`);
  console.log("\nNote: Use git reset --hard to revert changes if needed");
}

async function getCurrentConfig(): Promise<AndroidConfig> {
  // Read current app name from strings.xml
  const stringsContent = await Bun.file("android/app/src/main/res/values/strings.xml").text();
  const appNameMatch = stringsContent.match(/<string name="app_name">(.*?)<\/string>/);
  
  if (!appNameMatch?.[1]) {
    throw new Error("Could not determine current app name from strings.xml");
  }

  // Read current package name from build.gradle.kts
  const buildGradleContent = await Bun.file("android/app/build.gradle.kts").text();
  const packageMatch = buildGradleContent.match(/namespace = "(.*?)"/);

  if (!packageMatch?.[1]) {
    throw new Error("Could not determine current package name from build.gradle.kts");
  }

  return {
    appName: appNameMatch[1],
    packageName: packageMatch[1]
  };
}

async function main() {
  try {
    // Parse command line options
    const options = parseOptions();

    // Read new config
    const newConfig = await readConfig(options.config);

    // Read current app config from files
    const currentConfig = await getCurrentConfig();

    // Validate new config
    console.log("Validating configuration...");
    const configValidation = validateAndroidConfig(newConfig);
    if (!configValidation.isValid) {
      console.error("Configuration validation failed:");
      configValidation.errors.forEach((error: string) => console.error(`- ${error}`));
      process.exit(1);
    }

    // Validate file system
    console.log("Validating file system...");
    const fsValidation = await validateFileSystem();
    if (!fsValidation.isValid) {
      console.error("File system validation failed:");
      fsValidation.errors.forEach((error: string) => console.error(`- ${error}`));
      process.exit(1);
    }

    // Generate changes
    console.log("Generating required changes...");
    const changes = await generateChanges(
      currentConfig.packageName,
      newConfig.packageName,
      currentConfig.appName,
      newConfig.appName
    );

    // Print changes
    printChanges(currentConfig, newConfig);

    // If dry run, exit here
    if (options.dryRun) {
      console.log("\nDry run completed. No changes were made.");
      process.exit(0);
    }

    // Ask for confirmation
    const answer = await new Promise(resolve => {
      process.stdout.write("\nDo you want to proceed with these changes? [y/N] ");
      process.stdin.once("data", data => {
        resolve(data.toString().trim().toLowerCase());
      });
    });

    if (answer !== "y") {
      console.log("Operation cancelled.");
      process.exit(0);
    }

    // Apply changes
    console.log("Applying changes...");
    await applyChanges(changes);

    // Verify changes
    console.log("Verifying changes...");
    const verified = await verifyChanges(changes);
    
    if (!verified) {
      console.error("Failed to verify changes. Use 'git reset --hard' to revert.");
      process.exit(1);
    }

    console.log("\nAll changes applied successfully!");
    console.log(`App renamed from ${currentConfig.appName} to ${newConfig.appName}`);
    console.log(`Package renamed from ${currentConfig.packageName} to ${newConfig.packageName}`);

  } catch (err) {
    const error = err as Error;
    console.error("Error:", error.message);
    process.exit(1);
  }
}

main().catch(console.error);