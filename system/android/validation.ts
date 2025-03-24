import type { AndroidConfig, ValidationResult } from "./types";

/**
 * Validates Java package name format
 * Rules:
 * - Must be lowercase
 * - Can contain letters, numbers, and dots
 * - Each segment must start with a letter
 * - No consecutive dots
 * - At least one dot (requires multiple segments)
 */
export function validatePackageName(packageName: string): ValidationResult {
  const errors: string[] = [];
  
  if (!packageName) {
    errors.push("Package name cannot be empty");
    return { isValid: false, errors };
  }

  // Check lowercase
  if (packageName !== packageName.toLowerCase()) {
    errors.push("Package name must be lowercase");
  }

  // Check for valid characters and format
  const packageNameRegex = /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$/;
  if (!packageNameRegex.test(packageName)) {
    errors.push(
      "Package name must contain only lowercase letters, numbers, and dots. " +
      "Each segment must start with a letter and contain at least two segments."
    );
  }

  // Check for consecutive dots
  if (packageName.includes("..")) {
    errors.push("Package name cannot contain consecutive dots");
  }

  return {
    isValid: errors.length === 0,
    errors
  };
}

/**
 * Validates app name
 * Rules:
 * - Non-empty
 * - No special characters except spaces and hyphens
 * - Maximum length of 50 characters
 */
export function validateAppName(appName: string): ValidationResult {
  const errors: string[] = [];

  if (!appName) {
    errors.push("App name cannot be empty");
    return { isValid: false, errors };
  }

  if (appName.length > 50) {
    errors.push("App name cannot be longer than 50 characters");
  }

  // Allow letters, numbers, spaces, and hyphens
  const appNameRegex = /^[a-zA-Z0-9\s-]+$/;
  if (!appNameRegex.test(appName)) {
    errors.push("App name can only contain letters, numbers, spaces, and hyphens");
  }

  return {
    isValid: errors.length === 0,
    errors
  };
}

/**
 * Validates the entire Android configuration
 */
export function validateAndroidConfig(config: AndroidConfig): ValidationResult {
  const errors: string[] = [];

  const packageNameValidation = validatePackageName(config.packageName);
  const appNameValidation = validateAppName(config.appName);

  errors.push(...packageNameValidation.errors);
  errors.push(...appNameValidation.errors);

  return {
    isValid: errors.length === 0,
    errors
  };
}

/**
 * Validates that all required files exist
 */
export async function validateFileSystem(): Promise<ValidationResult> {
  const errors: string[] = [];
  const requiredFiles = [
    "android/app/build.gradle.kts",
    "android/app/src/main/AndroidManifest.xml",
    "android/app/src/main/res/values/strings.xml",
    "android/settings.gradle.kts"
  ];

  for (const file of requiredFiles) {
    try {
      const stat = await Bun.file(file).exists();
      if (!stat) {
        errors.push(`Required file not found: ${file}`);
      }
    } catch (err) {
      const error = err as Error;
      errors.push(`Error accessing file ${file}: ${error.message}`);
    }
  }

  return {
    isValid: errors.length === 0,
    errors
  };
}