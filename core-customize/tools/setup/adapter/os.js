import "zx/globals";
import process from "process";

export const $$ = $({ verbose: true, nothrow: true });

export function isWindows() {
  return process.platform === "win32";
}

function exitOnError(message, result) {
  if (result.exitCode != 0) {
    console.error(message);
    console.error(result.stderr);
    process.exit(result.exitCode);
  }

  return result;
}

export async function createDirectory(name) {
  return $$`mkdir -p ${name}`.then((result) =>
    exitOnError(
      `mkdir -p ${name} failed: ${result.exitCode}. Exiting...`,
      result,
    ),
  );
}

export async function runGradleTask(task, params = []) {
  const promise =
    $$`./gradlew --no-daemon --console=rich ${task} ${params}`.pipe(
      process.stdout,
    );

  return promise.then((result) =>
    exitOnError(
      `./gradlew ${task} failed: ${result.exitCode}. Exiting...`,
      result,
    ),
  );
}

export async function runAntTask(task, params = []) {
  const promise = $$`set +u &&  . ./setantenv.sh && ant ${task} ${params}`.pipe(
    process.stdout,
  );

  return promise.then((result) =>
    exitOnError(`ant ${task} failed: ${result.exitCode}. Exiting...`, result),
  );
}
