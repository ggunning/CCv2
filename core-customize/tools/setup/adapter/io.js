import fs from "fs";
import path from "path";
import property from "properties-parser";

export function writeToFile(filePath, content) {
  const dirPath = path.dirname(filePath);
  if (!fs.existsSync(dirPath)) {
    this.createDirectory(dirPath);
  }

  fs.writeFileSync(filePath, content);
}

export function readFile(filePath) {
  try {
    return fs.readFileSync(filePath, "utf-8");
  } catch (e) {
    console.error(e);
    throw new Error(`${filePath} is not a valid file`);
  }
}

export function createDirectory(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

export function move(sourcePath, targetPath) {
  fs.renameSync(sourcePath, targetPath);
}

export function deleteDirectory(path) {
  fs.rmSync(path, { recursive: true });
}

export function deleteFile(path) {
  fs.unlinkSync(path);
}

export function getFilesRecursively(directory) {
  const files = [];
  fs.readdirSync(directory).forEach((file) => {
    const absolute = path.join(directory, file);
    if (fs.statSync(absolute).isDirectory()) {
      return this.getFilesRecursively(absolute);
    } else {
      return files.push(absolute);
    }
  });
  return files;
}

export function setProperty(filePath, key, value) {
  this.setProperties(filePath, { [key]: value });
}

export function setProperties(filePath, propertyMap) {
  try {
    const file = property.createEditor(filePath);
    Object.entries(propertyMap).forEach(([key, value]) => {
      if (Array.isArray(value)) {
        file.set(key, value.join(","));
      } else {
        file.set(key, value);
      }
    });
    file.save();
  } catch (e) {
    console.error(e);
    throw new Error(`${filePath} is not a valid file`);
  }
}

export function getProperty(filePath, key) {
  try {
    const file = property.createEditor(filePath);
    return file.get(key);
  } catch (e) {
    console.error(e);
    throw new Error(`${filePath} is not a valid file`);
  }
}
