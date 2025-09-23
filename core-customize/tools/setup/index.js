#!/usr/bin/env zx

import path from "path";
import process from "process";
import inquirer from "inquirer";
import inquirerSearchList from "inquirer-search-list";

import * as operatingSystemIoAdapter from "./adapter/os.js";
import antHelper from "./helper/antHelper.js";
import boilerplateHelper from "./helper/boilerplateHelper.js";
import extensionInfoXmlHelper from "./helper/extensionInfoXmlHelper.js";
import projectPropertiesHelper from "./helper/projectPropertiesHelper.js";
import garbageRemover from "./helper/garbageRemover.js";
import localExtensions from "./helper/localExtensions.js";
import manifestHelper from "./helper/manifestHelper.js";

const $$ = $({ verbose: true });

if (operatingSystemIoAdapter.isWindows()) {
  console.error(`
    This script is not supported on Windows.
    Please run in a Linux/Bash environment (e.g. WSL or VM).`);
  process.exit(1);
}

//START: Configuration
const baseGitUrl = "git@gitlab.sybit.dev:clients/";
const companyLookupURL =
  "https://autocomplete.clearbit.com/v1/companies/suggest?query=";
const javaPackageRegex = /^(?!.*\.\.)([a-z_][a-z_0-9]*)(\.[a-z_][a-z_0-9]*)*$/i;
//END: Configuration

inquirer.registerPrompt("search-list", inquirerSearchList);

const initialWorkingDirectory = process.cwd();

const repoRoot = path.dirname(path.dirname(__dirname));
let gitUrl = (await $$`git remote get-url origin`).stdout.trim();

function cleanupStringForPackage(input) {
  return input.replace(/[^a-z0-9]+/gi, "_");
}

if (typeof gitUrl !== "string" || !gitUrl.startsWith(baseGitUrl)) {
  console.info(`"${gitUrl}": ${typeof gitUrl}`);
  console.error("Ensure that you used ssh to checkout the repository");
  console.error(
    'The repository must be under the global "Clients" namespace in GitLab.',
  );
  process.exit(1);
}

const gitPathStripped = gitUrl.substring(baseGitUrl.length);
const customerName = gitPathStripped.substring(0, gitPathStripped.indexOf("/"));

console.info("Determined customer Name: " + customerName);

const companiesResult = await fetch(
  companyLookupURL + encodeURIComponent(customerName),
);
const companies = await companiesResult.json();

let companyUrlString = "__custom__";
if (Array.isArray(companies)) {
  let choices = companies.map((c) => ({
    name: `${c.name}: ${c.domain}`,
    value: c.domain,
  }));
  choices.push({ name: "Enter manually!", value: companyUrlString });
  companyUrlString = (
    await inquirer.prompt([
      {
        type: "search-list",
        message: "Select url",
        name: "companyUrl",
        choices: choices,
      },
    ])
  ).companyUrl;
}

if (companyUrlString === "__custom__") {
  companyUrlString = (
    await inquirer.prompt([
      {
        type: "input",
        message: "Please enter the company url",
        name: "companyUrl",
        validate: (urlString) => {
          let url;
          try {
            url = new URL(urlString);
          } catch (error) {
            return "Invalid URL";
          }

          if (
            url.toString().length > 0 &&
            (url.protocol === "" ||
              url.protocol === "http:" ||
              url.protocol === "https:")
          ) {
            return true;
          }
          return 'URL must have a domain part and protocol "http" or "https" or nothing';
        },
      },
    ])
  ).companyUrl;
}

if (!companyUrlString.startsWith("http")) {
  companyUrlString = "https://" + companyUrlString;
}

const companyDomain = new URL(companyUrlString).host;
if (typeof companyDomain !== "string") {
  console.error("failed to extract domain from: " + companyUrlString);
  process.exit(2);
}

const customerPackage = companyDomain
  .split(".")
  .map(cleanupStringForPackage)
  .reverse()
  .slice(0, 2)
  .join(".");

const projectName = cleanupStringForPackage(gitPathStripped.split("/", 3)[1]);

let basePackage = customerPackage + "." + projectName;

const answers = await inquirer.prompt([
  {
    type: "input",
    message: "Java Package",
    name: "package",
    default: basePackage,
    validate(input) {
      if (typeof input === "string" && javaPackageRegex.test(input)) {
        return true;
      }
      return "Invalid java package name";
    },
  },
  {
    type: "checkbox",
    message: "Select Feature",
    name: "features",
    choices: [
      { name: "Include sap integration extensions", value: "sapci" },
      { name: "Generate customer OCC extension", value: "customocc" },
    ],
  },
]);
basePackage = answers.package;
const feature_sapci = answers.features.includes("sapci");
const feature_customOcc = answers.features.includes("customocc");

cd(repoRoot);

boilerplateHelper.setProjectProperties(
  `${cleanupStringForPackage(customerName)}-${projectName}`,
);
await boilerplateHelper.installProject();

if (feature_sapci) {
  localExtensions.addIntegrationExtensions();
}

await antHelper.generateExtensions(customerName, basePackage);
localExtensions.addBaseProjectExtensions(customerName);
extensionInfoXmlHelper.setupBaseExtensions(customerName, feature_sapci);
projectPropertiesHelper.configureExtensions(customerName, basePackage);

if (feature_customOcc) {
  await antHelper.generateOccExtension(customerName, basePackage);
  localExtensions.addOccProjectExtensions(customerName);
  extensionInfoXmlHelper.setupOccExtension(customerName);
}

// clean up model changes and related files
garbageRemover.removeModels(customerName);
garbageRemover.removeUnusedFiles(customerName, basePackage);
garbageRemover.removeSpringEntries(customerName);

projectPropertiesHelper.configureGradle(basePackage);
manifestHelper.setupCoreManifest(customerName, basePackage, feature_customOcc);

console.info("Removing template files...");
await $$`rm -rf docs/adl docs/img/development_infrastructure_diagram.* docs/img/jenkins_pipeline_setup.png docs/*.md catalog-info.yaml mkdocs.yml`;

console.info("Creating initial commit...");
await $$`git rm -rf --cached 'tools/setup/'`;
await $$`git add .`;

await $$`echo -e "\n# Ignore any added files in the generated hybris code\nhybris/bin/custom/**/hybrisgen/\n" >> .gitignore`;
await $$`git add .gitignore`;
await $$`git commit -m 'chore(automated-setup): automated setup done'`;

cd(initialWorkingDirectory);
console.info("All done! Happy Coding!");
