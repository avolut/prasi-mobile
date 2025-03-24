export interface AndroidConfig {
  appName: string;
  packageName: string;
}

export interface CLIOptions {
  config: string;
  dryRun: boolean;
}

export interface FileChange {
  path: string;
  oldContent: string;
  newContent: string;
}

export interface DirectoryChange {
  oldPath: string;
  newPath: string;
  type: 'move' | 'create' | 'delete';
}

export interface RenameChanges {
  fileChanges: FileChange[];
  directoryChanges: DirectoryChange[];
}

export interface ValidationResult {
  isValid: boolean;
  errors: string[];
}