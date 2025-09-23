import path from "path";
import { DOMParser, XMLSerializer } from "@xmldom/xmldom";
import * as fileIoAdapter from "../adapter/io.js";
import hybrisPath from "./hybrisPath.js";

export class DefaultExtensionInfoXmlHelper {
  setupBaseExtensions(customerName, useCPI) {
    this.setupExtensionInfoXml(hybrisPath.extensionDir("core", customerName), [
      "acceleratorcms",
      "sapmodel",
    ]);
    const backofficeDependencies = [
      "acceleratorbackoffice",
      "sapmodelbackoffice",
      "adaptivesearchbackoffice",
      "cmsbackoffice",
      "b2bcommercebackoffice",
      "commerceservicesbackoffice",
      "pcmbackoffice",
    ];
    if (useCPI) {
      backofficeDependencies.push("integrationmonitoringbackoffice");
    }
    this.setupExtensionInfoXml(
      hybrisPath.extensionDir("backoffice", customerName),
      backofficeDependencies,
    );
    this.setupExtensionInfoXml(
      hybrisPath.extensionDir("facades", customerName),
      [
        "acceleratorfacades",
        hybrisPath.cleanupStringForExtName(customerName) + "core",
      ],
    );
    this.setupExtensionInfoXml(
      hybrisPath.extensionDir("fulfilmentprocess", customerName),
      [hybrisPath.cleanupStringForExtName(customerName) + "core"],
    );
    this.setupExtensionInfoXml(hybrisPath.extensionDir("data", customerName), [
      hybrisPath.cleanupStringForExtName(customerName) + "core",
      "commerceservices",
    ]);
  }

  setupOccExtension(customerName) {
    this.setupExtensionInfoXml(hybrisPath.extensionDir("occ", customerName), [
      hybrisPath.cleanupStringForExtName(customerName) + "facades",
      "commercewebservices",
    ]);
  }

  remapBrokenExtensionNames(extension) {
    if (extension.toLowerCase() == "arecodeploymentscriptsmanagerbackoffice") {
      return "arecodeploymentscriptsbackoffice";
    }
    return extension;
  }

  setupExtensionInfoXml(folder, dependencies) {
    const file = path.join(folder, "extensioninfo.xml");

    const parser = new DOMParser();
    const extensionInfoXml = parser.parseFromString(
      fileIoAdapter.readFile(file),
      "text/xml",
    );
    const extensionTag =
      extensionInfoXml.documentElement.getElementsByTagName("extension")[0];

    const metaTags = extensionTag.getElementsByTagName("meta");
    for (let i = 0; i < metaTags.length; i++) {
      const metaTag = metaTags[i];
      if (metaTag.getAttribute("key") == "modulegen-name") {
        extensionTag.removeChild(metaTag);
      }
    }

    const requiredExtensions =
      extensionTag.getElementsByTagName("requires-extension") || [];
    for (let i = 0; i < requiredExtensions.length; i++) {
      const requiredExtensionTag = requiredExtensions[i];
      if (requiredExtensionTag.getAttribute("name").substring(0, 4) == "yacc") {
        extensionTag.removeChild(requiredExtensionTag);
      }
    }

    extensionTag.appendChild(extensionInfoXml.createTextNode("\n"));
    dependencies.forEach((extension) => {
      extensionTag.insertBefore(
        extensionInfoXml.createTextNode("\n\t\t"),
        extensionTag.firstChild,
      );
      const node = extensionInfoXml.createElement("requires-extension");
      node.setAttribute("name", extension);
      extensionTag.insertBefore(node, extensionTag.firstChild);
    });
    extensionTag.insertBefore(
      extensionInfoXml.createTextNode("\n\t\t"),
      extensionTag.firstChild,
    );

    const serializer = new XMLSerializer();
    fileIoAdapter.writeToFile(
      file,
      serializer.serializeToString(extensionInfoXml),
    );
  }
}

export default new DefaultExtensionInfoXmlHelper();
