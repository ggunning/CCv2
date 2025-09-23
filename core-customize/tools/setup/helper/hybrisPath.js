import path from "path";

class DefaultHybrisPath {
  hybrisGenIn(extension, src, customerName, packagePath) {
    return path.join(
      this.extensionDir(extension, customerName),
      src,
      ...packagePath.split("."),
      extension,
      "hybrisgen",
    );
  }

  cleanupStringForExtName(txt) {
    return String(txt)
      .toLowerCase()
      .replace(/[^a-z]/g, "");
  }

  extensionDir(extension, customerName) {
    return path.join(
      "hybris",
      "bin",
      "custom",
      customerName,
      this.cleanupStringForExtName(customerName + extension),
    );
  }
}

export default new DefaultHybrisPath();
