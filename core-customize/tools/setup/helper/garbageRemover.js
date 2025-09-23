/// <reference lib="dom" />
import path from "path";
import { DOMParser, XMLSerializer } from "@xmldom/xmldom";
import * as fileIoAdapter from "../adapter/io.js";
import hybrisPath from "./hybrisPath.js";

export class DefaultGarbageRemover {
  removeModels(customerName) {
    console.info(
      "Removing Models from " +
        hybrisPath.cleanupStringForExtName(customerName) +
        "core-items.xml...",
    );
    const itemsXmlFile = path.join(
      hybrisPath.extensionDir("core", customerName),
      "resources",
      hybrisPath.cleanupStringForExtName(customerName) + "core-items.xml",
    );

    const parser = new DOMParser();
    const itemsXmlRoot = parser.parseFromString(
      fileIoAdapter.readFile(itemsXmlFile),
      "text/xml",
    );

    this.clearChildren(itemsXmlRoot.getElementsByTagName("collectiontypes")[0]);
    this.clearChildren(itemsXmlRoot.getElementsByTagName("enumtypes")[0]);
    this.clearChildren(itemsXmlRoot.getElementsByTagName("itemtypes")[0]);

    const serializer = new XMLSerializer();
    fileIoAdapter.writeToFile(
      itemsXmlFile,
      serializer.serializeToString(itemsXmlRoot),
    );
  }

  removeUnusedFiles(customerName, packagePath) {
    console.info(
      "Removing unused classes from " +
        hybrisPath.cleanupStringForExtName(customerName) +
        "core...",
    );
    const corePath = hybrisPath.hybrisGenIn(
      "core",
      "src",
      customerName,
      packagePath,
    );
    const solrProviders = path.join(
      corePath,
      "search",
      "solrfacetsearch",
      "provider",
      "impl",
    );
    fileIoAdapter.deleteFile(
      path.join(solrProviders, "ColorFacetDisplayNameProvider.java"),
    );
    fileIoAdapter.deleteFile(
      path.join(solrProviders, "ColorFacetValueProvider.java"),
    );
    fileIoAdapter.deleteFile(
      path.join(solrProviders, "GenderValueProvider.java"),
    );
    fileIoAdapter.deleteFile(
      path.join(
        corePath,
        "strategies",
        "impl",
        "ApparelSizeProductReferenceTargetStrategy.java",
      ),
    );

    const coreTestPath = hybrisPath.hybrisGenIn(
      "core",
      "testsrc",
      customerName,
      packagePath,
    );
    fileIoAdapter.deleteFile(
      path.join(coreTestPath, "batch", "task", "BatchIntegrationTest.java"),
    );

    console.info(
      "Removing unused classes from " +
        hybrisPath.cleanupStringForExtName(customerName) +
        "facades...",
    );
    const facadesPath = hybrisPath.hybrisGenIn(
      "facades",
      "src",
      customerName,
      packagePath,
    );
    fileIoAdapter.deleteFile(
      path.join(facadesPath, "populators", "ApparelProductPopulator.java"),
    );
    fileIoAdapter.deleteFile(
      path.join(facadesPath, "suggestion", "SimpleSuggestionFacade.java"),
    );
    fileIoAdapter.deleteFile(
      path.join(
        facadesPath,
        "suggestion",
        "impl",
        "DefaultSimpleSuggestionFacade.java",
      ),
    );

    const facadesTestPath = hybrisPath.hybrisGenIn(
      "facades",
      "testsrc",
      customerName,
      packagePath,
    );
    fileIoAdapter.deleteFile(
      path.join(
        facadesTestPath,
        "suggestion",
        "impl",
        "DefaultSimpleSuggestionFacadeTest.java",
      ),
    );
    fileIoAdapter.deleteFile(
      path.join(
        facadesTestPath,
        "suggestion",
        "DefaultSimpleSuggestionFacadeIntegrationTest.java",
      ),
    );
  }
  removeSpringEntries(customerName) {
    const parser = new DOMParser();
    const serializer = new XMLSerializer();

    const coreSpringFileName =
      hybrisPath.cleanupStringForExtName(customerName) + "core-spring.xml";

    console.info(
      "Removing unused spring beans from " + coreSpringFileName + "...",
    );
    const coreSpringFile = path.join(
      hybrisPath.extensionDir("core", customerName),
      "resources",
      coreSpringFileName,
    );
    const coreSpringXmlRoot = parser.parseFromString(
      fileIoAdapter.readFile(coreSpringFile),
      "text/xml",
    );

    this.removeSpringDefinition(coreSpringXmlRoot, "colorFacetValueProvider");
    this.removeSpringDefinition(
      coreSpringXmlRoot,
      "colorFacetDisplayNameProvider",
    );
    this.removeSpringDefinition(coreSpringXmlRoot, "genderValueProvider");
    this.removeSpringDefinition(
      coreSpringXmlRoot,
      "apparelSizeProductReferenceTargetStrategy",
    );

    fileIoAdapter.writeToFile(
      coreSpringFile,
      serializer.serializeToString(coreSpringXmlRoot),
    );

    const facadesSpringFileName =
      hybrisPath.cleanupStringForExtName(customerName) + "facades-spring.xml";

    console.info(
      "Removing unused spring beans from " + facadesSpringFileName + "...",
    );
    const facadesSpringFile = path.join(
      hybrisPath.extensionDir("facades", customerName),
      "resources",
      facadesSpringFileName,
    );
    const facadesSpringXmlRoot = parser.parseFromString(
      fileIoAdapter.readFile(facadesSpringFile),
      "text/xml",
    );

    this.removeSpringDefinition(
      facadesSpringXmlRoot,
      "defaultSimpleSuggestionFacade",
    );
    this.removeSpringDefinition(
      facadesSpringXmlRoot,
      "apparelProductPopulator",
    );

    fileIoAdapter.writeToFile(
      facadesSpringFile,
      serializer.serializeToString(facadesSpringXmlRoot),
    );
  }

  removeSpringDefinition(springRoot, definition) {
    const beans = springRoot.getElementsByTagName("bean");

    for (let i = 0; i < beans.length; i++) {
      const bean = beans[i];
      if (bean.getAttribute("id") == definition) {
        bean.parentNode.removeChild(bean);
      }
    }

    const refs = springRoot.getElementsByTagName("ref");
    for (let i = 0; i < refs.length; i++) {
      const ref = refs[i];
      if (ref.getAttribute("bean") == definition) {
        ref.parentNode.removeChild(ref);
      }
    }

    const aliases = springRoot.getElementsByTagName("alias");
    for (let i = 0; i < aliases.length; i++) {
      const alias = aliases[i];
      if (alias.getAttribute("name") == definition) {
        const aliasDefinition = alias.getAttribute("alias");
        alias.parentNode.removeChild(alias);
        this.removeSpringDefinition(springRoot, aliasDefinition);
      }
    }

    const properties = springRoot.getElementsByTagName("property");
    for (let i = 0; i < properties.length; i++) {
      const property = properties[i];
      if (property.getAttribute("ref") == definition) {
        property.parentNode.parentNode.removeChild(property.parentNode);
      }
    }
  }

  clearChildren(xmlNode) {
    while (xmlNode.hasChildNodes()) {
      xmlNode.removeChild(xmlNode.firstChild);
    }
  }
}

export default new DefaultGarbageRemover();
