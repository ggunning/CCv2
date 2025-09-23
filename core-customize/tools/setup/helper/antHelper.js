import "zx/globals";
import path from "path";
import * as fileIoAdapter from "../adapter/io.js";
import * as operatingSystemIoAdapter from "../adapter/os.js";
import hybrisPath from "./hybrisPath.js";

export class DefaultAntHelper {
  async generateExtensions(customerName, packagePath) {
    await operatingSystemIoAdapter.createDirectory(
      `hybris/bin/custom/${customerName}`,
    );

    await this.generateExtension(
      "yacceleratorcore",
      "core",
      customerName,
      packagePath,
      [],
    );
    await this.generateExtension(
      "ybackoffice",
      "backoffice",
      customerName,
      packagePath,
      [
        "-Dextgen.ybackoffice.sassextension=true",
        "-Dextgen.ybackoffice.samplewidget=false",
        "-Dextgen.ybackoffice.whitelabel=true",
      ],
    );
    await this.generateExtension(
      "yacceleratorfacades",
      "facades",
      customerName,
      packagePath,
      [],
    );
    await this.generateExtension(
      "yacceleratorfulfilmentprocess",
      "fulfilmentprocess",
      customerName,
      packagePath,
      [],
    );
    await this.generateExtension(
      "yvoid",
      "data",
      customerName,
      packagePath,
      [],
    );
  }

  async generateOccExtension(customerName, packagePath) {
    await this.generateExtension("yocc", "occ", customerName, packagePath, []);
  }

  async generateExtension(
    template,
    extension,
    customerName,
    packagePath,
    additionalParams,
  ) {
    console.info("Generating " + extension + " extension");
    cd("hybris/bin/platform");

    const extName = hybrisPath.cleanupStringForExtName(
      customerName + extension,
    );
    const params = [
      `-Dinput.template=${template}`,
      `-Dinput.name=${extName}`,
      `-Dinput.package=${packagePath}.${extension}.hybrisgen`,
      ...additionalParams,
    ];

    await operatingSystemIoAdapter.runAntTask("extgen", params);
    cd("../custom");
    fileIoAdapter.move(extName, path.join(customerName, extName));
    cd("../../..");
  }
}

export default new DefaultAntHelper();
