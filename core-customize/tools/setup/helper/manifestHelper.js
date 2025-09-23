import path from "path";
import * as fileIoAdapter from "../adapter/io.js";
import hybrisPath from "./hybrisPath.js";

export class DefaultManifestHelper {
  setupCoreManifest(customerName, packagePath, useOcc) {
    console.info("Setting up core manifest...");
    const manifestFile = path.join(".", "manifest.json");
    const manifest = JSON.parse(fileIoAdapter.readFile(manifestFile));

    manifest.tests.extensions = [
      ...manifest.tests.extensions,
      hybrisPath.cleanupStringForExtName(customerName) + "core",
      hybrisPath.cleanupStringForExtName(customerName) + "backoffice",
      hybrisPath.cleanupStringForExtName(customerName) + "facades",
      hybrisPath.cleanupStringForExtName(customerName) + "fulfilmentprocess",
      hybrisPath.cleanupStringForExtName(customerName) + "data",
    ];

    if (useOcc) {
      manifest.tests.extensions.push(
        hybrisPath.cleanupStringForExtName(customerName) + "occ",
      );
    }

    manifest.tests.packages.push(packagePath + ".*");

    fileIoAdapter.writeToFile(manifestFile, JSON.stringify(manifest, null, 2));
  }
}

export default new DefaultManifestHelper();
