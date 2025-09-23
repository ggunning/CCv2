import path from "path";
import * as fileIoAdapter from "../adapter/io.js";
import hybrisPath from "./hybrisPath.js";

export class ProjectPropertiesHelper {
  configureExtensions(customerName, basePackage) {
    this.setProjectProperties(hybrisPath.extensionDir("core", customerName), {
      emailContextPackageName: `${basePackage}.facades.hybrisgen.process.email.context`,
    });
  }

  configureGradle(basePackage) {
    const gradleProperties = path.join(".", "gradle.properties");
    const propertyMap = {
      "testclasses.packages": ["de.sybit.*", `${basePackage}.*`],
    };
    fileIoAdapter.setProperties(gradleProperties, propertyMap);
  }

  setProjectProperties(folder, propertyMap) {
    const projectProperties = path.join(folder, "project.properties");
    fileIoAdapter.setProperties(projectProperties, propertyMap);
  }
}

export default new ProjectPropertiesHelper();
