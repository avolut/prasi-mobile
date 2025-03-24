# Android App Rename CLI

A command-line tool for renaming Android app package name and app name based on configuration.

## Features

- ✅ Validates package name format and app name
- ✅ Shows preview of changes (dry-run mode)
- ✅ Verifies changes after application
- ✅ Git-friendly: Use `git reset --hard` to revert changes

## Prerequisites

- [Bun](https://bun.sh) runtime installed
- Android project structure following standard conventions
- Git repository initialized (for reverting changes if needed)

## Usage

1. Create or modify `config.json` with your desired app name and package name:

```json
{
  "appName": "NewAppName",
  "packageName": "com.example.newapp"
}
```

2. Run the tool:

```bash
# Show changes without applying them
bun run dev --dry-run

# Apply changes
bun run dev

# Use custom config file
bun run dev --config my-config.json
```

3. Revert changes if needed:

```bash
git reset --hard  # Reverts all changes
```

## Command Line Options

- `--config, -c`: Path to config file (default: config.json)
- `--dry-run, -d`: Show changes without applying them

## File Changes

The tool modifies the following files:

- `android/app/build.gradle.kts`
  - `namespace`
  - `applicationId`
- `android/app/src/main/AndroidManifest.xml`
  - Activity package name
- `android/app/src/main/res/values/strings.xml`
  - App name string resource
- `android/settings.gradle.kts`
  - Root project name

Additionally, it moves Kotlin source files to match the new package structure.

## Error Handling

The tool performs several validation checks:

1. Package name format validation
   - Lowercase letters only
   - Valid separator dots
   - Each segment starts with a letter
   - At least two segments

2. App name validation
   - Non-empty
   - Valid characters
   - Maximum length check

3. File system validation
   - Required files exist
   - Proper permissions
   - Valid file content

## Example

```bash
# Create config.json
echo '{
  "appName": "MyNewApp",
  "packageName": "com.example.mynewapp"
}' > config.json

# Preview changes
bun run dev --dry-run

# Apply changes
bun run dev

# If needed, revert changes
git reset --hard
