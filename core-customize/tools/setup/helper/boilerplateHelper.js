import * as operatingSystemIoAdapter from "../adapter/os.js";
import * as fileIoAdapter from "../adapter/io.js";

export class DefaultBoilerplateHelper {
  setProjectProperties(projectName) {
    fileIoAdapter.setProperty(
      "settings.gradle",
      "rootProject.name",
      `'${projectName}'`,
    );
  }

  async installProject() {
    console.info(`Downloading commerce and installing...`);
    return operatingSystemIoAdapter.runGradleTask("install");
  }

  async buildProject() {
    console.info(`Running clean build...`);
    return operatingSystemIoAdapter.runGradleTask("clean build");
  }
}

export default new DefaultBoilerplateHelper();
