use crate::prelude::*;

use crate::ci_gen::not_default_branch;
use crate::ci_gen::runs_on;
use crate::ci_gen::secret;
use crate::ci_gen::step;
use crate::ci_gen::variables::ENSO_AG_GRID_LICENSE_KEY;
use crate::ci_gen::variables::ENSO_MAPBOX_API_TOKEN;
use crate::ci_gen::RunStepsBuilder;
use crate::ci_gen::RunnerType;
use crate::ci_gen::RELEASE_CLEANING_POLICY;
use crate::engine::env;
use crate::ide::web::env::VITE_ENSO_AG_GRID_LICENSE_KEY;
use crate::ide::web::env::VITE_ENSO_MAPBOX_API_TOKEN;

use heck::ToKebabCase;
use ide_ci::actions::workflow::definition::cancel_workflow_action;
use ide_ci::actions::workflow::definition::Access;
use ide_ci::actions::workflow::definition::Job;
use ide_ci::actions::workflow::definition::JobArchetype;
use ide_ci::actions::workflow::definition::Permission;
use ide_ci::actions::workflow::definition::RunnerLabel;
use ide_ci::actions::workflow::definition::Step;
use ide_ci::actions::workflow::definition::Strategy;
use ide_ci::actions::workflow::definition::Target;
use ide_ci::cache::goodie::graalvm;



/// This should be kept as recent as possible.
///
/// macOS must use a recent version of Electron Builder to have Python 3 support. Otherwise, build
/// would fail due to Python 2 missing.
///
/// We keep old versions of Electron Builder for Windows to avoid NSIS installer bug:
/// https://github.com/electron-userland/electron-builder/issues/6865
const ELECTRON_BUILDER_MACOS_VERSION: Version = Version::new(24, 6, 4);

/// Target runners set (or just a single runner) for a job.
pub trait RunsOn: 'static + Debug {
    /// A strategy that will be used for the job.
    ///
    /// Needs to be customized only for matrix jobs.
    fn strategy(&self) -> Option<Strategy> {
        None
    }

    /// Labels required on the runner to run this job.
    fn runs_on(&self) -> Vec<RunnerLabel>;

    /// A name that will be added to the job name.
    ///
    /// Should not be used if there is per-os matrix.
    fn job_name_suffix(&self) -> Option<String> {
        None
    }
}

impl RunsOn for RunnerLabel {
    fn runs_on(&self) -> Vec<RunnerLabel> {
        vec![*self]
    }

    fn job_name_suffix(&self) -> Option<String> {
        match self {
            RunnerLabel::MacOS => Some("MacOS".to_string()),
            RunnerLabel::Linux => Some("Linux".to_string()),
            RunnerLabel::Windows => Some("Windows".to_string()),
            RunnerLabel::MacOSLatest => Some("MacOSLatest".to_string()),
            RunnerLabel::LinuxLatest => Some("LinuxLatest".to_string()),
            RunnerLabel::WindowsLatest => Some("WindowsLatest".to_string()),
            // Other labels are not OS-specific, so None.
            RunnerLabel::SelfHosted
            | RunnerLabel::Engine
            | RunnerLabel::X64
            | RunnerLabel::Arm64
            | RunnerLabel::Benchmark
            | RunnerLabel::Metarunner
            | RunnerLabel::MatrixOs => None,
        }
    }
}

impl RunsOn for OS {
    fn runs_on(&self) -> Vec<RunnerLabel> {
        (*self, Arch::X86_64).runs_on()
    }
    fn job_name_suffix(&self) -> Option<String> {
        Some(self.to_string())
    }
}

impl RunsOn for (OS, Arch) {
    fn runs_on(&self) -> Vec<RunnerLabel> {
        match self {
            (OS::MacOS, Arch::X86_64) => runs_on(OS::MacOS, RunnerType::GitHubHosted),
            (os, Arch::X86_64) => runs_on(*os, RunnerType::SelfHosted),
            (OS::MacOS, Arch::AArch64) => {
                let mut ret = runs_on(OS::MacOS, RunnerType::SelfHosted);
                ret.push(RunnerLabel::Arm64);
                ret
            }
            _ => panic!("Unsupported OS/arch combination: {self:?}"),
        }
    }

    fn job_name_suffix(&self) -> Option<String> {
        Some(format!("{}, {}", self.0, self.1))
    }
}

pub fn plain_job(
    runs_on: impl RunsOn,
    name: impl Into<String>,
    command_line: impl Into<String>,
) -> Job {
    RunStepsBuilder::new(command_line).build_job(name, runs_on)
}

/// Pretty print arguments to `./run` that will invoke SBT with the given command.
///
/// Meant to be used together with [`RunStepsBuilder::new`].
///
/// ```
/// use enso_build::ci_gen::job::sbt_command;
/// assert_eq!(sbt_command("verifyLicensePackages"), "backend sbt '--' verifyLicensePackages");
/// ```
pub fn sbt_command(command: impl AsRef<str>) -> String {
    // Note that we put -- in quotes to avoid issues with powershell (which is default on Windows).
    // Otherwise, pwsh would handle `--` by itself, rather than passing it to build script's args.
    // See: https://stackoverflow.com/questions/15780174/powershell-command-line-parameters-and
    format!("backend sbt '--' {}", command.as_ref())
}

/// Expose variables for cloud configuration, needed during the dashboard build.
pub fn expose_cloud_vars(step: Step) -> Step {
    use crate::ide::web::env::*;
    step.with_variable_exposed(ENSO_CLOUD_REDIRECT)
        .with_variable_exposed(ENSO_CLOUD_ENVIRONMENT)
        .with_variable_exposed(ENSO_CLOUD_API_URL)
        .with_variable_exposed(ENSO_CLOUD_CHAT_URL)
        .with_variable_exposed(ENSO_CLOUD_SENTRY_DSN)
        .with_variable_exposed(ENSO_CLOUD_STRIPE_KEY)
        .with_variable_exposed(ENSO_CLOUD_COGNITO_USER_POOL_ID)
        .with_variable_exposed(ENSO_CLOUD_COGNITO_USER_POOL_WEB_CLIENT_ID)
        .with_variable_exposed(ENSO_CLOUD_COGNITO_DOMAIN)
        .with_variable_exposed(ENSO_CLOUD_COGNITO_REGION)
}

/// Expose variables for the GUI build.
pub fn expose_gui_vars(step: Step) -> Step {
    let step = step
        .with_variable_exposed_as(ENSO_AG_GRID_LICENSE_KEY, VITE_ENSO_AG_GRID_LICENSE_KEY)
        .with_variable_exposed_as(ENSO_MAPBOX_API_TOKEN, VITE_ENSO_MAPBOX_API_TOKEN);

    // GUI includes the cloud-delivered dashboard.
    expose_cloud_vars(step)
}

#[derive(Clone, Copy, Debug)]
pub struct CancelWorkflow;

impl JobArchetype for CancelWorkflow {
    fn job(&self, _target: Target) -> Job {
        Job {
            name: "Cancel Previous Runs".into(),
            // It is important that this particular job runs pretty much everywhere (we use x64,
            // as all currently available GH runners have this label). If we limited it only to
            // our self-hosted machines (as we usually do), it'd be enqueued after other jobs
            // and wouldn't be able to cancel them.
            runs_on: vec![RunnerLabel::LinuxLatest],
            steps: vec![cancel_workflow_action()],
            r#if: Some(not_default_branch()),
            ..default()
        }
        // Necessary permission to cancel a run, as per:
        // https://docs.github.com/en/rest/actions/workflow-runs?apiVersion=2022-11-28#cancel-a-workflow-run
        .with_permission(Permission::Actions, Access::Write)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct VerifyLicensePackages;
impl JobArchetype for VerifyLicensePackages {
    fn job(&self, target: Target) -> Job {
        RunStepsBuilder::new(sbt_command("verifyLicensePackages"))
            .build_job("Verify License Packages", target)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct JvmTests {
    pub graal_edition: graalvm::Edition,
}

impl JobArchetype for JvmTests {
    fn job(&self, target: Target) -> Job {
        let graal_edition = self.graal_edition;
        let job_name = format!("JVM Tests ({graal_edition})");
        let mut job = RunStepsBuilder::new("backend test jvm")
            .customize(move |step| vec![step, step::engine_test_reporter(target, graal_edition)])
            .build_job(job_name, target)
            .with_permission(Permission::Checks, Access::Write);
        match graal_edition {
            graalvm::Edition::Community => job.env(env::GRAAL_EDITION, graalvm::Edition::Community),
            graalvm::Edition::Enterprise =>
                job.env(env::GRAAL_EDITION, graalvm::Edition::Enterprise),
        }
        job
    }

    fn key(&self, (os, arch): Target) -> String {
        format!(
            "{}-{}-{os}-{arch}",
            self.id_key_base(),
            self.graal_edition.to_string().to_kebab_case()
        )
    }
}

#[derive(Clone, Copy, Debug)]
pub struct StandardLibraryTests {
    pub graal_edition: graalvm::Edition,
}

impl JobArchetype for StandardLibraryTests {
    fn job(&self, target: Target) -> Job {
        let graal_edition = self.graal_edition;
        let job_name = format!("Standard Library Tests ({graal_edition})");
        let mut job = RunStepsBuilder::new("backend test standard-library")
            .customize(move |step| {
                let main_step = step
                    .with_secret_exposed_as(
                        secret::ENSO_LIB_S3_AWS_REGION,
                        crate::libraries_tests::s3::env::ENSO_LIB_S3_AWS_REGION,
                    )
                    .with_secret_exposed_as(
                        secret::ENSO_LIB_S3_AWS_ACCESS_KEY_ID,
                        crate::libraries_tests::s3::env::ENSO_LIB_S3_AWS_ACCESS_KEY_ID,
                    )
                    .with_secret_exposed_as(
                        secret::ENSO_LIB_S3_AWS_SECRET_ACCESS_KEY,
                        crate::libraries_tests::s3::env::ENSO_LIB_S3_AWS_SECRET_ACCESS_KEY,
                    );
                vec![main_step, step::stdlib_test_reporter(target, graal_edition)]
            })
            .build_job(job_name, target)
            .with_permission(Permission::Checks, Access::Write);
        match graal_edition {
            graalvm::Edition::Community => job.env(env::GRAAL_EDITION, graalvm::Edition::Community),
            graalvm::Edition::Enterprise =>
                job.env(env::GRAAL_EDITION, graalvm::Edition::Enterprise),
        }
        job
    }

    fn key(&self, (os, arch): Target) -> String {
        format!(
            "{}-{}-{os}-{arch}",
            self.id_key_base(),
            self.graal_edition.to_string().to_kebab_case()
        )
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Lint;

impl JobArchetype for Lint {
    fn job(&self, target: Target) -> Job {
        plain_job(target, "Lint", "lint")
    }
}

#[derive(Clone, Copy, Debug)]
pub struct NativeTest;

impl JobArchetype for NativeTest {
    fn job(&self, target: Target) -> Job {
        plain_job(target, "Native Rust tests", "wasm test --no-wasm")
    }
}

#[derive(Clone, Copy, Debug)]
pub struct GuiTest;

impl JobArchetype for GuiTest {
    fn job(&self, target: Target) -> Job {
        plain_job(target, "GUI tests", "gui test")
    }
}


#[derive(Clone, Copy, Debug)]
pub struct NewGuiBuild;

impl JobArchetype for NewGuiBuild {
    fn job(&self, target: Target) -> Job {
        let command = "gui build";
        RunStepsBuilder::new(command)
            .customize(|step| vec![expose_gui_vars(step)])
            .build_job("GUI build", target)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct WasmTest;

impl JobArchetype for WasmTest {
    fn job(&self, target: Target) -> Job {
        plain_job(target, "WASM tests", "wasm test --no-native")
    }
}

#[derive(Clone, Copy, Debug)]
pub struct BuildBackend;

impl JobArchetype for BuildBackend {
    fn job(&self, target: Target) -> Job {
        plain_job(target, "Build Backend", "backend get")
    }
}

#[derive(Clone, Copy, Debug)]
pub struct UploadBackend;

impl JobArchetype for UploadBackend {
    fn job(&self, target: Target) -> Job {
        RunStepsBuilder::new("backend upload")
            .cleaning(RELEASE_CLEANING_POLICY)
            .build_job("Upload Backend", target)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct DeployRuntime;

impl JobArchetype for DeployRuntime {
    fn job(&self, target: Target) -> Job {
        RunStepsBuilder::new("release deploy-runtime")
            .customize(|step| {
                vec![step
                    .with_secret_exposed_as(secret::CI_PRIVATE_TOKEN, ide_ci::github::GITHUB_TOKEN)
                    .with_env("ENSO_BUILD_ECR_REPOSITORY", crate::aws::ecr::runtime::NAME)
                    .with_secret_exposed_as(
                        secret::ECR_PUSH_RUNTIME_ACCESS_KEY_ID,
                        "AWS_ACCESS_KEY_ID",
                    )
                    .with_secret_exposed_as(
                        secret::ECR_PUSH_RUNTIME_SECRET_ACCESS_KEY,
                        "AWS_SECRET_ACCESS_KEY",
                    )
                    .with_env("AWS_DEFAULT_REGION", crate::aws::ecr::runtime::REGION)]
            })
            .build_job("Upload Runtime to ECR", target)
    }
}

pub fn expose_os_specific_signing_secret(os: OS, step: Step) -> Step {
    match os {
        OS::Windows => step
            .with_secret_exposed_as(secret::WINDOWS_CERT_PATH, &crate::ide::web::env::WIN_CSC_LINK)
            .with_secret_exposed_as(
                secret::WINDOWS_CERT_PASSWORD,
                &crate::ide::web::env::WIN_CSC_KEY_PASSWORD,
            ),
        OS::MacOS => step
            .with_secret_exposed_as(
                secret::APPLE_CODE_SIGNING_CERT,
                &crate::ide::web::env::CSC_LINK,
            )
            .with_secret_exposed_as(
                secret::APPLE_CODE_SIGNING_CERT_PASSWORD,
                &crate::ide::web::env::CSC_KEY_PASSWORD,
            )
            .with_secret_exposed_as(
                secret::APPLE_NOTARIZATION_USERNAME,
                &crate::ide::web::env::APPLEID,
            )
            .with_secret_exposed_as(
                secret::APPLE_NOTARIZATION_PASSWORD,
                &crate::ide::web::env::APPLEIDPASS,
            )
            .with_secret_exposed_as(
                secret::APPLE_NOTARIZATION_TEAM_ID,
                &crate::ide::web::env::APPLETEAMID,
            )
            .with_env(crate::ide::web::env::CSC_IDENTITY_AUTO_DISCOVERY, "true")
            .with_env(crate::ide::web::env::CSC_FOR_PULL_REQUEST, "true"),
        _ => step,
    }
}

/// The sequence of steps that bumps the version of the Electron-Builder to
/// [`ELECTRON_BUILDER_MACOS_VERSION`].
pub fn bump_electron_builder() -> Vec<Step> {
    let npm_install =
        Step { name: Some("NPM install".into()), run: Some("npm install".into()), ..default() };
    let uninstall_old = Step {
        name: Some("Uninstall old Electron Builder".into()),
        run: Some("npm uninstall --save --workspace enso electron-builder".into()),
        ..default()
    };
    let command = format!(
        "npm install --save-dev --workspace enso electron-builder@{ELECTRON_BUILDER_MACOS_VERSION}"
    );
    let install_new =
        Step { name: Some("Install new Electron Builder".into()), run: Some(command), ..default() };
    vec![npm_install, uninstall_old, install_new]
}

/// Prepares the packaging steps for the given OS.
///
/// This involves:
/// * exposing secrets necessary for code signing and notarization;
/// * exposing variables defining cloud environment for dashboard;
/// * (macOS only) bumping the version of the Electron Builder to
///   [`ELECTRON_BUILDER_MACOS_VERSION`].
pub fn prepare_packaging_steps(os: OS, step: Step) -> Vec<Step> {
    let step = expose_gui_vars(step);
    let step = expose_os_specific_signing_secret(os, step);
    let mut steps = Vec::new();
    if os == OS::MacOS {
        steps.extend(bump_electron_builder());
    }
    steps.push(step);
    steps
}

/// Convenience for [`prepare_packaging_steps`].
///
/// This function is useful when you want to use [`prepare_packaging_steps`] as a closure.
pub fn with_packaging_steps(os: OS) -> impl FnOnce(Step) -> Vec<Step> {
    move |step| prepare_packaging_steps(os, step)
}

#[derive(Clone, Copy, Debug)]
pub struct PackageIde;

impl JobArchetype for PackageIde {
    fn job(&self, target: Target) -> Job {
        RunStepsBuilder::new(
            "ide build --backend-source current-ci-run --gui-upload-artifact false",
        )
        .customize(with_packaging_steps(target.0))
        .build_job("Package New IDE", target)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct CiCheckBackend {
    pub graal_edition: graalvm::Edition,
}

impl JobArchetype for CiCheckBackend {
    fn job(&self, target: Target) -> Job {
        let job_name = format!("Engine ({})", self.graal_edition);
        let mut job = RunStepsBuilder::new("backend ci-check").build_job(job_name, target);
        match self.graal_edition {
            graalvm::Edition::Community => job.env(env::GRAAL_EDITION, graalvm::Edition::Community),
            graalvm::Edition::Enterprise =>
                job.env(env::GRAAL_EDITION, graalvm::Edition::Enterprise),
        }
        job
    }

    fn key(&self, (os, arch): Target) -> String {
        format!(
            "{}-{}-{os}-{arch}",
            self.id_key_base(),
            self.graal_edition.to_string().to_kebab_case()
        )
    }
}
