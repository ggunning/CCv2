import "zx/globals";
import { DOMParser, XMLSerializer } from "@xmldom/xmldom";
import * as fileIoAdapter from "../adapter/io.js";
import hybrisPath from "./hybrisPath.js";

export class DefaultLocalExtensionHelper {
  addBaseProjectExtensions(customerName) {
    this.appendExtensions(
      [
        hybrisPath.cleanupStringForExtName(customerName) + "core",
        hybrisPath.cleanupStringForExtName(customerName) + "backoffice",
        hybrisPath.cleanupStringForExtName(customerName) + "facades",
        hybrisPath.cleanupStringForExtName(customerName) + "data",
        hybrisPath.cleanupStringForExtName(customerName) + "fulfilmentprocess",
      ],
      "Project Extensions",
    );
  }

  addIntegrationExtensions() {
    this.appendExtensions(
      [
        "odata2services",
        "odata2webservices",
        "sapcpiproductexchange",
        "integrationmonitoringbackoffice",
      ],
      "Integration API Extensions",
    );
  }

  addOccProjectExtensions(customerName) {
    this.appendExtensions([
      hybrisPath.cleanupStringForExtName(customerName) + "occ",
    ]);
  }

  appendExtensions(extensionNames, comment) {
    cd("config/_common");
    const parser = new DOMParser();
    const extensionsXml = parser.parseFromString(
      fileIoAdapter.readFile("localextensions.xml"),
      "text/xml",
    );

    const extensionsTag =
      extensionsXml.documentElement.getElementsByTagName("extensions")[0];
    if (comment !== undefined) {
      extensionsTag.appendChild(extensionsXml.createTextNode("\n\n\t\t"));
      extensionsTag.appendChild(extensionsXml.createComment(comment));
    }
    extensionNames.forEach((extension) => {
      extensionsTag.appendChild(extensionsXml.createTextNode("\n\t\t"));
      const node = extensionsXml.createElement("extension");
      node.setAttribute("name", extension);
      extensionsTag.appendChild(node);
    });
    extensionsTag.appendChild(extensionsXml.createTextNode("\n\t"));
    const serializer = new XMLSerializer();
    fileIoAdapter.writeToFile(
      "localextensions.xml",
      serializer.serializeToString(extensionsXml),
    );
    cd("../..");
  }
}

export default new DefaultLocalExtensionHelper();
