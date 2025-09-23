import path from "path";
import { DOMParser, XMLSerializer } from "@xmldom/xmldom";
import * as fileIoAdapter from "../adapter/io.js";

export class DefaultBuildCallbackCleaner {
  removeClasspathFileGeneration(file) {
    const parser = new DOMParser();
    const buildcallbackXml = parser.parseFromString(
      fileIoAdapter.readFile(file),
      "text/xml",
    );

    const macrodefs = buildcallbackXml.getElementsByTagName("macrodef");
    for (let i = 0; i < macrodefs.length; i++) {
      const macrodef = macrodefs[i];
      const name = macrodef.getAttribute("name");
      if (name.endsWith("_clean_classpath_file")) {
        buildcallbackXml.removeChild(macrodef);
      } else if (name.endsWith("_after_clean")) {
        this.removeClasspathGenerationFromAfterCleanMacrodef(macrodef);
      }
    }

    const serializer = new XMLSerializer();
    fileIoAdapter.writeToFile(
      file,
      serializer.serializeToString(buildcallbackXml),
    );
  }

  removeClasspathGenerationFromAfterCleanMacrodef(macrodef) {
    const macrodefname = macrodef.getAttribute("name");
    const extname = macrodefname.replace(/_after_clean$/, "");

    const creates = macrodef.getElementsByTagName(
      "create_extension_classpath_file",
    );
    for (let i = 0; i < creates.length; i++) {
      const element = creates[i];
      element.parentNode.removeChild(element);
    }

    const cleans = macrodef.getElementsByTagName(
      `${extname}_clean_classpath_file`,
    );
    for (let i = 0; i < creates.length; i++) {
      const element = cleans[i];
      element.parentNode.removeChild(element);
    }

    const echos = macrodef.getElementsByTagName("echo");
    for (let i = 0; i < echos.length; i++) {
      const element = echos[i];
      if (element.getAttribute("message").indexOf(" .classpath ") !== -1) {
        element.parentNode.removeChild(element);
      }
    }
  }
}

export default new DefaultBuildCallbackCleaner();
