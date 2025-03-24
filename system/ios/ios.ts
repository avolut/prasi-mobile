import * as fs from 'fs';
import * as path from 'path';

interface Config {
  appName: string;
  packageName: string;
}

async function updateIOSConfig(config: Config) {
  const iosDir = 'ios';
  const appName = config.appName;
  const packageName = config.packageName;

  // Function to replace content in a file
  async function replaceInFile(filePath: string, searchValue: string | RegExp, replaceValue: string) {
    try {
      let fileContent = fs.readFileSync(filePath, 'utf8');
      const newContent = fileContent.replace(searchValue, replaceValue);
      fs.writeFileSync(filePath, newContent, 'utf8');
      console.log(`Updated ${filePath}`);
    } catch (error) {
      console.error(`Failed to update ${filePath}: ${error}`);
    }
  }

  // 1. Update the bundle identifier in the project.pbxproj file
  const projectPath = path.join(iosDir, 'PrasiMobile.xcodeproj', 'project.pbxproj');
  const bundleIdentifierSearch = /PRODUCT_BUNDLE_IDENTIFIER = (.*?);/;
  const bundleIdentifierReplace = `PRODUCT_BUNDLE_IDENTIFIER = ${packageName};`;
  await replaceInFile(projectPath, bundleIdentifierSearch, bundleIdentifierReplace);

  // 2. Update the display name in the Info.plist file (if applicable)
  // NOTE: This assumes the display name is directly in the Info.plist.
  //       If the display name is localized, this will need to be adjusted.
  const infoPlistPath = path.join(iosDir, 'PrasiMobile', 'Info.plist');
  const displayNameSearch = /<key>CFBundleDisplayName<\/key>\s*<string>(.*?)<\/string>/;
  const displayNameReplace = `<key>CFBundleDisplayName<\/key>\n\t<string>${appName}</string>`;
  await replaceInFile(infoPlistPath, displayNameSearch, displayNameReplace);

  // 3. Update the product name in the Info.plist file
  const productNameSearch = /<key>CFBundleName<\/key>\s*<string>(.*?)<\/string>/;
  const productNameReplace = `<key>CFBundleName<\/key>\n\t<string>${appName}</string>`;
  await replaceInFile(infoPlistPath, productNameSearch, productNameReplace);

  console.log('IOS config updated successfully');
}

// Read config.json
const configFile = fs.readFileSync('config.json', 'utf8');
const config: Config = JSON.parse(configFile);

// Call the update function
updateIOSConfig(config).catch(error => {
  console.error('Failed to update IOS config:', error);
});
