import groovy.transform.Field

/**
 * Fixed root of the LKP result archive on the SUT. The Jenkins job's folder path + name
 * (env.JOB_NAME, spaces -> '_') is appended to it, and the whole tree is created at the start
 * of the run (see prepareRunBackupDir) if it does not exist. Per-build layout:
 *
 *   <PIPELINE_DATA_DIR>/<JOB_NAME, spaces->_>/Run_<BUILD_NUMBER>/<RUN_NAME>/
 *   e.g. /tests/jenkins/workspace/host_regression_2_0/OpenAnolis_Distro/kernel_6_6/5_level_pagetable/Run_3/BASE_ONLY_LKP
 *
 * where RUN_NAME is BASE_ONLY_LKP / BASE_VM / BASE_VM_LKP / PATCH_ONLY_LKP / … .
 */
@Field String PIPELINE_DATA_DIR = '/tests/jenkins/workspace/'

/**
 * How long the GRUB menu stays on screen, IN SECONDS, before the default kernel is booted.
 * Re-applied before every boot selection (see applyGrubMenuTimeout). The default kernel set
 * by this pipeline is persistent, so a kernel that cannot boot is only recoverable from the
 * console — the menu has to stay up long enough to pick the previous kernel by hand.
 * Set to 0 to leave whatever timeout the SUT already has untouched.
 */
@Field int GRUB_MENU_TIMEOUT = 15

/**
 * false — persistent default (grubby --set-default / grub-set-default), the behaviour this
 *         pipeline shipped with. A kernel that cannot boot needs recovery at the console.
 * true  — boot the test kernel for the NEXT BOOT ONLY (grub2-reboot / grub-reboot): only
 *         next_entry is written, the persistent default is left untouched, so a kernel that
 *         panics costs one failed boot instead of a rescue session (grub falls back to the
 *         persistent default on the boot after).
 *
 * NOTE: a one-time boot deliberately SKIPS the grub menu — grub consumes next_entry and boots
 * that entry directly, so no kernel list is offered at the console. Keep this false while you
 * need to pick kernels by hand at boot. When one-time boot is unavailable on a SUT (no
 * grub2-reboot / grub-reboot, or a grub too old to honour next_entry), the code falls back to
 * the persistent default automatically.
 */
@Field boolean ONE_TIME_BOOT = true

/**
 * Kernel release per build prefix (with_patch / base_patch), read out of the package file
 * list at install time by installKernelFromBuild and reused by getInstalledKernel — an exact
 * answer instead of picking the newest /boot/vmlinuz-* by mtime.
 */
@Field Map INSTALLED_KERNELS = [:]

/**
 * Kernel packages this run built are copied here and reused to install into the current-OS
 * guest. When the pipeline builds (RPM_KERNEL_BUILD=BUILD_BOTH), the directory is cleared once
 * and both fresh packages (base + patch) are saved into it. BUILT_PKG_PATHS maps the build
 * prefix (base_patch / with_patch) to the saved package path; JENKINS_RPM_CLEARED guards the
 * one-time clear so the second build does not wipe the first build's saved package.
 */
@Field String JENKINS_RPM_DIR = '/vms/jenkins_rpm'
@Field Map BUILT_PKG_PATHS = [:]
@Field boolean JENKINS_RPM_CLEARED = false

/**
 * Golden qcow2 images live here; disposable per-VM overlays are created beside them and the
 * golden images are NEVER written to. One filename per OS in each of two families:
 *   no-LKP — guests without lkp-tests preinstalled (LKP runs only on the host).
 *   LKP    — guests with lkp-tests preinstalled (LKP runs on host AND inside the guests).
 * A VM run spawns one guest per OS (<OS>_stressVM) from the family matching the run type,
 * plus EXTRA_VMS extra guests cloned from the host's own running-OS golden.
 */
@Field String VM_IMAGE_DIR = '/vms/jenkins_qcow2'
@Field Map VM_IMAGES_NOLKP = [
    anolis   : 'anolis_jenkins_vm.qcow2',
    Rocky    : 'Rocky_jenk_vm.qcow2',
    Opencloud: 'Opencloud_jenk_VM.qcow2',
    Euler    : 'Euler_jenk_vm.qcow2',
    Velinux  : 'Velinux_jenk_vm.qcow2',
    Ubuntu   : 'Ubuntu_jenk_vm.qcow2',
]
@Field Map VM_IMAGES_LKP = [
    anolis   : 'anolis_jen_lkp_vm.qcow2',
    Rocky    : 'Rocky_jen_lkp_vm.qcow2',
    Opencloud: 'Opencloud_jen_lkp_vm.qcow2',
    Euler    : 'Euler_jen_lkp_vm.qcow2',
    Velinux  : 'Velinux_jen_lkp_vm.qcow2',
    Ubuntu   : 'Ubuntu_jen_lkp_vm.qcow2',
]

// Gating helpers for Declarative when { expression { … } }

/** NULL_NO_KERNEL_WORKFLOW is the "do nothing" mode: every gate below is off for it. */
boolean lkpRunOptionIn(List<String> options) {
    return params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' &&
        options.contains(params.LKP_RUN_OPTIONS)
}

boolean needsBootTargetWorkflow() {
    return lkpRunOptionIn([
        'PATCH_KERNEL_LKP', 'BASE_KERNEL_AND_LKP',
        'PATCH_KERNEL_NOLKP_IN_VM', 'BASE_KERNEL_NOLKP_IN_VM',
        'PATCH_KERNEL_LKP_IN_VM', 'BASE_KERNEL_LKP_IN_VM'
    ])
}

boolean needsLkpAfterBoot() {
    return lkpRunOptionIn(['PATCH_KERNEL_LKP', 'BASE_KERNEL_AND_LKP'])
}

boolean needsSingleKernelVmWorkflow() {
    return lkpRunOptionIn([
        'PATCH_KERNEL_NOLKP_IN_VM', 'BASE_KERNEL_NOLKP_IN_VM',
        'PATCH_KERNEL_LKP_IN_VM', 'BASE_KERNEL_LKP_IN_VM'
    ])
}

boolean needsBothKernelVmWorkflow() {
    return lkpRunOptionIn(['BOTH_KERNELS_NOLKP_IN_VM', 'BOTH_KERNELS_LKP_IN_VM'])
}

boolean needsAllRunsWorkflow() {
    return lkpRunOptionIn(['ALL_RUNS'])
}

boolean needsAllBaseWorkflow() {
    return lkpRunOptionIn(['ALL_BASE'])
}

boolean needsAllPatchWorkflow() {
    return lkpRunOptionIn(['ALL_PATCH'])
}

// The full-sequence stages (host LKP → VM no-LKP → VM LKP) are shared:
// ALL_RUNS runs both halves, ALL_BASE only the BASE half, ALL_PATCH only the PATCH half.
boolean needsAllSequenceBase() {
    needsAllRunsWorkflow() || needsAllBaseWorkflow()
}

boolean needsAllSequencePatch() {
    needsAllRunsWorkflow() || needsAllPatchWorkflow()
}

boolean bootOptionRequiresVmCreation() {
    needsSingleKernelVmWorkflow() || needsBothKernelVmWorkflow() || needsAllRunsWorkflow() ||
        needsAllBaseWorkflow() || needsAllPatchWorkflow()
}

boolean needsKernelRepo() {
    params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' &&
        params.RPM_KERNEL_BUILD != 'MANUAL_BOTH_NO_BUILD'
}

boolean needsBothBasePatchLkp() {
    return lkpRunOptionIn(['BOTH_BASE_PATCH_LKP'])
}

/** True when the selected LKP_RUN_OPTIONS exercises the BASE kernel (BASE_*, BOTH_*, ALL_RUNS/ALL_BASE). */
boolean runNeedsBaseKernel() {
    return lkpRunOptionIn(['BASE_KERNEL_AND_LKP', 'BASE_KERNEL_NOLKP_IN_VM', 'BASE_KERNEL_LKP_IN_VM']) ||
        needsBothBasePatchLkp() || needsBothKernelVmWorkflow() || needsAllRunsWorkflow() || needsAllBaseWorkflow()
}

/** True when the selected LKP_RUN_OPTIONS exercises the PATCH kernel (PATCH_*, BOTH_*, ALL_RUNS/ALL_PATCH). */
boolean runNeedsPatchKernel() {
    return lkpRunOptionIn(['PATCH_KERNEL_LKP', 'PATCH_KERNEL_NOLKP_IN_VM', 'PATCH_KERNEL_LKP_IN_VM']) ||
        needsBothBasePatchLkp() || needsBothKernelVmWorkflow() || needsAllRunsWorkflow() || needsAllPatchWorkflow()
}

/**
 * WORKLOAD_TYPE gating. 'ALL' selects every workload; the combined choices
 * (e.g. hackbench_and_ebizzy) match by name, so adding a workload to a combined
 * choice needs no change here.
 */
boolean runsWorkload(String workload) {
    def selected = params.WORKLOAD_TYPE?.trim()?.toLowerCase() ?: ''
    return selected == 'all' || selected.contains(workload)
}

boolean runsHackbench() {
    runsWorkload('hackbench')
}

boolean runsEbizzy() {
    runsWorkload('ebizzy')
}

boolean runsUnixbench() {
    runsWorkload('unixbench')
}

/** When false, skip grubby/reboot for the matching ALREADY_BOOTED kernel; re-enabled before dual-kernel PATCH boot. */
@Field boolean boot = true

boolean isDualKernelBootWorkflow() {
    needsBothBasePatchLkp() || needsBothKernelVmWorkflow() || needsAllRunsWorkflow()
}

void initBootFlag() {
    boot = true
    if (params.ALREADY_BOOTED == 'BASE_KERNEL' || params.ALREADY_BOOTED == 'PATCH_KERNEL') {
        boot = false
    }
    echo "ALREADY_BOOTED=${params.ALREADY_BOOTED} → boot flag=${boot}"
}

/** Re-enable boot before the second (PATCH) boot in dual-kernel LKP_RUN_OPTIONS. */
void enableBootForPatchPhase() {
    boot = true
    echo "boot flag re-enabled for PATCH phase (boot=${boot})"
}

boolean shouldSkipBoot(String kernelRole) {
    if (kernelRole == 'PATCH' && isDualKernelBootWorkflow()) {
        // ALREADY_BOOTED=PATCH_KERNEL does not apply to dual-kernel flows.
        return false
    }
    if (!boot) {
        if (kernelRole == 'BASE' && params.ALREADY_BOOTED == 'BASE_KERNEL') {
            return true
        }
        if (kernelRole == 'PATCH' && params.ALREADY_BOOTED == 'PATCH_KERNEL') {
            return true
        }
    }
    return false
}

String bootTargetKernelRole() {
    switch (params.LKP_RUN_OPTIONS) {
        case 'PATCH_KERNEL_LKP':
        case 'PATCH_KERNEL_NOLKP_IN_VM':
        case 'PATCH_KERNEL_LKP_IN_VM':
            return 'PATCH'
        case 'BASE_KERNEL_AND_LKP':
        case 'BASE_KERNEL_NOLKP_IN_VM':
        case 'BASE_KERNEL_LKP_IN_VM':
            return 'BASE'
        default:
            return null
    }
}

def verifyExpectedKernelRunning(String kernelRole) {
    def expected = kernelRole == 'BASE' ? env.BASE_KERNEL : env.PATCH_KERNEL
    requireKernel(expected, "${kernelRole}_KERNEL")
    if (shouldSkipBoot(kernelRole)) {
        echo "ALREADY_BOOTED=${params.ALREADY_BOOTED}: skipping the boot to ${kernelRole}; only checking that the running kernel is the resolved ${kernelRole} one."
    }
    verifyRunningKernel(expected, kernelRole)
}

/** Parent directory for scratch/work on the agent; the kernel tree comes from EXISTING_REPO_PATH (env.DIR). */
@Field String PIPELINE_BASE_DIR = '/home/amd'

/** Report label and backup-dir suffix per LKP run context; keeps the two spellings in sync. */
@Field Map LKP_CONTEXTS = [
    vm_no_lkp_image: [label: 'LKP_with_VM_image_no_lkp_inside', suffix: 'VM'],
    vm_lkp_image   : [label: 'LKP_with_VM_image_lkp_inside',    suffix: 'VM_LKP'],
    host           : [label: 'LKP_without_VM',                  suffix: 'ONLY_LKP']
]

// The agent comes from the NODE_LABEL parameter; every stage that touches the SUT runs on
// it, so the SUT's IP is only ever needed for the log (see resolveNodeIpFromAgent).

pipeline {
    agent none

    options {
        disableConcurrentBuilds()
    }

    parameters {
        booleanParam(
            name: 'PREREQUISITES_CONFIRMED',
            defaultValue: false,
            description: '''BEFORE YOU RUN — confirm these one-time prerequisites on the SUT/agent (NODE_LABEL). The build fails fast in "Validate parameters" if any are missing.

1) 12 golden VM qcow2 images must be in  /vms/jenkins_qcow2/*  (6 no-LKP + 6 LKP; one per OS: anolis/Rocky/Opencloud/Euler/Velinux/Ubuntu). If missing, copy them from the development host and fix ownership:
       scp amd@10.86.26.102:/home/amd/vol1/images/jenkins/*  /vms/jenkins_qcow2/
       chown qemu:qemu /vms/jenkins_qcow2/*

2) LKP result Excel template must be at  /vms/jenkins_excel_template/lkp_result_template.xlsx  (see LKP_RESULT_TEMPLATE_PATH below). If missing, copy it from the development host:
       scp amd@10.86.26.102:/home/amd/vol1/images/jenkins_excel_template/lkp_result_template.xlsx  /vms/jenkins_excel_template/

3) The result-archive root path /tests/jenkins/workspace/ must exist and be writable by the agent user (per-build subdirectories under it are created automatically from JOB_NAME).'''
        )
        string(
            name: 'NODE_LABEL',
            defaultValue: '9dee',
            trim: true,
            description: 'Jenkins agent label of the SUT. SUT IP is auto-resolved from the agent via `hostname -I`.'
        )
        string(
            name: 'JIRA_ID',
            defaultValue: '',
            trim: true,
            description: 'Jira ticket number (e.g. 5748) used in the LKP result workbook name DEAE_<JIRA_ID>_<OS>_<kernel>_<pipe>.xlsx.'
        )
        string(
            name: 'LKP_RESULT_TEMPLATE_PATH',
            defaultValue: '/vms/jenkins_excel_template/lkp_result_template.xlsx',
            trim: true,
            description: 'Path (on the agent) to the LKP result Excel template. Copied to /home/amd/DEAE_<JIRA_ID>_<OS>_<kernel>_<pipe>.xlsx on first use, then edited in place.'
        )
        choice(
            name: 'RPM_KERNEL_BUILD',
            choices: [
                'NULL_NO_KERNEL_WORKFLOW',
                'BUILD_BOTH',
                'MANUAL_BOTH_NO_BUILD'
            ],
            description: '''Kernel source (default NULL does nothing):
NULL_NO_KERNEL_WORKFLOW — skip build/install/boot/LKP.
BUILD_BOTH — build patch + base from EXISTING_REPO_PATH; kernel names discovered from install.
MANUAL_BOTH_NO_BUILD — no build; use preinstalled kernels named in MANUAL_*_KERNEL.'''
        )
        string(
            name: 'EXISTING_REPO_PATH',
            defaultValue: '',
            trim: true,
            description: 'Required for BUILD_BOTH. Absolute path to the kernel git checkout on the agent (env.DIR).'
        )
        string(
            name: 'EXISTING_REPO_BRANCH',
            defaultValue: '',
            trim: true,
            description: 'Required when BUILD_BOTH. Branch to checkout in EXISTING_REPO_PATH before build.'
        )
        string(
            name: 'BASE_HEAD_SHA_ID',
            defaultValue: '',
            trim: true,
            description: 'Optional (BUILD_BOTH only). Commit SHA (7-40 hex) to reset the base build to; empty = HEAD. Repo auto-deepened if the SHA is missing.'
        )
        choice(
            name: 'CHOOSE_BUILD_CONFIG',
            choices: [
                'Velinux',
                'Anolis',
                'OpenEuler',
                'OpenCloud',
                'others'
            ],
            description: '''Distro config applied before packaging:
  Velinux   -> cp config.x86_64 .config && make olddefconfig
  Anolis    -> make anolis_defconfig
  OpenEuler -> make openeuler_defconfig
  OpenCloud -> make tencentconfig
  others    -> make <CUSTOM_CONFIG>
Debian/veLinux build-breaker symbols (cert keys, DEBUG_INFO_BTF, NET_VENDOR_NETRONOME) are auto-disabled after.'''
        )
        string(
            name: 'CUSTOM_CONFIG',
            defaultValue: '',
            trim: true,
            description: 'Make config target for CHOOSE_BUILD_CONFIG=others (e.g. x86_64_defconfig, allmodconfig). Ignored otherwise.'
        )
        text(
            name: 'EXTRA_CONFIGS_ENABLE',
            defaultValue: '',
            description: '''Optional. Configs to enable, one per line (.config format), on top of CHOOSE_BUILD_CONFIG. PATCH kernel only.
Accepted: CONFIG_FOO (=y), CONFIG_FOO=y|m|42|"string".'''
        )
        text(
            name: 'EXTRA_CONFIGS_DISABLE',
            defaultValue: '',
            description: '''Optional. Configs to disable, one per line, on top of CHOOSE_BUILD_CONFIG. PATCH kernel only.
Accepted: CONFIG_FOO or "# CONFIG_FOO is not set".'''
        )
        string(
            name: 'MANUAL_PATCH_KERNEL',
            defaultValue: '',
            trim: true,
            description: 'Preinstalled patch kernel release as in /boot/vmlinuz-<THIS>. Used by MANUAL_BOTH_NO_BUILD; empty otherwise.'
        )
        string(
            name: 'MANUAL_BASE_KERNEL',
            defaultValue: '',
            trim: true,
            description: 'Preinstalled base kernel release as in /boot/vmlinuz-<THIS>. Used by MANUAL_BOTH_NO_BUILD; empty otherwise.'
        )
        choice(
            name: 'VM_CREATION_REQUIRED',
            choices: ['NO', 'YES'],
            description: '''Spawn stress VMs (YES/NO).
YES spawns one guest per OS from golden images in /vms/jenkins_qcow2 (6 VMs, <OS>_stressVM) plus EXTRA_VMS cloned from the host OS. Golden images are read-only (guests are qcow2 overlays) and verified present at start.'''
        )
        choice(
            name: 'EXTRA_VMS',
            choices: ['0', '1', '2', '3', '4', '5'],
            description: 'Extra guests beyond the 6 per-OS VMs (total = 6 + x), cloned from the host-OS golden image and named <hostOS>_stressVM_e1..e<x>. Default 0.'
        )
        string(
            name: 'VM_LOGIN_CREDENTIALS_ID',
            defaultValue: 'vm-login',
            trim: true,
            description: 'Only when VM_CREATION_REQUIRED=YES: Jenkins "Username with password" credential ID used to SSH into the current-OS guest to install the kernel package.'
        )
        string(
            name: 'MANUAL_BASE_RPM_PATH',
            defaultValue: '',
            trim: true,
            description: 'Only when VM_CREATION_REQUIRED=YES and the kernel was NOT built by this pipe: path on the agent to the BASE kernel .rpm (RHEL-family host). Installed into the current-OS guest during a BASE VM run. If the kernel was built by this pipe earlier (BUILD_BOTH), find the .rpm in /vms/jenkins_rpm/ (the name contains "base_patch" or "base-patch"; only the latest BUILD_BOTH run is kept).'
        )
        string(
            name: 'MANUAL_BASE_DEB_PATH',
            defaultValue: '',
            trim: true,
            description: 'Only when VM_CREATION_REQUIRED=YES and the kernel was NOT built by this pipe: path on the agent to the BASE kernel .deb (Debian/veLinux host). Installed into the current-OS guest during a BASE VM run. If the kernel was built by this pipe earlier (BUILD_BOTH), find the .deb in /vms/jenkins_rpm/ (the name contains "base_patch" or "base-patch"; only the latest BUILD_BOTH run is kept).'
        )
        string(
            name: 'MANUAL_PATCH_RPM_PATH',
            defaultValue: '',
            trim: true,
            description: 'Only when VM_CREATION_REQUIRED=YES and the kernel was NOT built by this pipe: path on the agent to the PATCH kernel .rpm (RHEL-family host). Installed into the current-OS guest during a PATCH VM run. If the kernel was built by this pipe earlier (BUILD_BOTH), find the .rpm in /vms/jenkins_rpm/ (the name contains "with_patch" or "with-patch"; only the latest BUILD_BOTH run is kept).'
        )
        string(
            name: 'MANUAL_PATCH_DEB_PATH',
            defaultValue: '',
            trim: true,
            description: 'Only when VM_CREATION_REQUIRED=YES and the kernel was NOT built by this pipe: path on the agent to the PATCH kernel .deb (Debian/veLinux host). Installed into the current-OS guest during a PATCH VM run. If the kernel was built by this pipe earlier (BUILD_BOTH), find the .deb in /vms/jenkins_rpm/ (the name contains "with_patch" or "with-patch"; only the latest BUILD_BOTH run is kept).'
        )
        choice(
            name: 'ALREADY_BOOTED',
            choices: ['NO', 'BASE_KERNEL', 'PATCH_KERNEL'],
            description: '''Skip grubby/reboot if the SUT already runs the target kernel.
NO — always boot when required (default).
BASE_KERNEL — skip boot for BASE-target runs (verifies uname -r = BASE_KERNEL first).
PATCH_KERNEL — skip boot for PATCH-target runs. Neither applies to the PATCH half of BOTH_*/ALL_RUNS.'''
        )
        choice(
            name: 'LKP_RUN_OPTIONS',
            choices: [
                'BASE_KERNEL_AND_LKP',
                'BASE_KERNEL_NOLKP_IN_VM',
                'BASE_KERNEL_LKP_IN_VM',
                'PATCH_KERNEL_LKP',
                'PATCH_KERNEL_NOLKP_IN_VM',
                'PATCH_KERNEL_LKP_IN_VM',
                'BOTH_BASE_PATCH_LKP',
                'BOTH_KERNELS_NOLKP_IN_VM',
                'BOTH_KERNELS_LKP_IN_VM',
                'ALL_BASE',
                'ALL_PATCH',
                'ALL_RUNS'
            ],
            description: '''Boot/LKP after kernels resolve (ignored when RPM_KERNEL_BUILD=NULL):
BASE_KERNEL_AND_LKP / PATCH_KERNEL_LKP — boot target kernel + LKP on host.
BOTH_BASE_PATCH_LKP — LKP on BASE then PATCH (ends on PATCH).
*_NOLKP_IN_VM — boot target, run LKP in VMs from non-LKP qcow2, cleanup.
*_LKP_IN_VM — boot target, run LKP in VMs from LKP qcow2, cleanup.
BOTH_KERNELS_*_IN_VM — VM run on BASE then PATCH (self-contained per phase).
ALL_BASE / ALL_PATCH — that kernel: host LKP + both VM runs.
ALL_RUNS — BASE then PATCH: host LKP + both VM runs.'''
        )
        choice(
            name: 'WORKLOAD_TYPE',
            choices: ['hackbench', 'ebizzy', 'unixbench', 'hackbench_and_ebizzy', 'ALL'],
            description: '''LKP workload(s):
hackbench / ebizzy / unixbench — that one only (unixbench has no iterations param).
hackbench_and_ebizzy — both.
ALL — all three.'''
        )
        choice(
            name: 'HACKBENCH_ITERATIONS',
            choices: ['4', '8'],
            description: 'hackbench iterations; patched into lkp-tests/jobs/hackbench.yaml before split-job.'
        )
        choice(
            name: 'EBIZZY_ITERATIONS',
            choices: ['25', '50', '75', '100'],
            description: 'ebizzy iterations (the `x` suffix is added); patched into lkp-tests/jobs/ebizzy.yaml before split-job.'
        )
    }

    environment {
        BRANCH = "${params.EXISTING_REPO_BRANCH}"
        BASE_DIR = "${PIPELINE_BASE_DIR}"
        DIR = "${params.EXISTING_REPO_PATH}"
        BUILD_HOME = "${PIPELINE_BASE_DIR}/LKP_Automated"
        // NODE_IP is resolved at runtime from the labeled agent, not set here.
    }

    stages {

        stage('Resolve node IP from agent') {
            agent { label params.NODE_LABEL }
            steps {
                script {
                    resolveNodeIpFromAgent()
                }
            }
        }

        stage('Show LKP-running banner (motd)') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    showLkpMotdBanner()
                }
            }
        }

        // Validate every user-supplied path/option up front (right after the agent is
        // available for filesystem checks) so a bad EXISTING_REPO_PATH, VM qcow2 path,
        // MANUAL_* kernel, SHA, or option combo fails in seconds -- before we spend time
        // preparing backup dirs, detecting the OS, or installing prerequisites.
        stage('Validate parameters') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    validateRpmKernelParams()
                }
            }
        }

        stage('Prepare result backup dir') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    prepareRunBackupDir()
                    echo "RUN_BACKUP_DIR=${env.RUN_BACKUP_DIR}"
                }
            }
        }

        stage('Detect OS / boot method') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    env.BOOT_METHOD = detectBootMethod()
                    echo "BOOT_METHOD=${env.BOOT_METHOD} (grubby=RHEL-like Anolis/OpenCloud/OpenEuler; grub_debian=Ubuntu/Velinux)"
                }
            }
        }

        stage('Install prerequisites') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    installPrerequisites()
                }
            }
        }

        stage('NULL_NO_KERNEL_WORKFLOW (no-op)') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD == 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                echo 'RPM_KERNEL_BUILD=NULL_NO_KERNEL_WORKFLOW: skipping builds, install, boot, and LKP.'
            }
        }

        stage('Checkout kernel repo') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsKernelRepo() }
            }
            steps {
                sh '''
                set -eu
                if [ ! -d "${DIR}/.git" ]; then
                    echo "Kernel repo not found or not a git repo: ${DIR}"
                    exit 1
                fi
                # Git 2.35+ refuses commands when repo dir owner != current user (common for Jenkins vs /home/amd/... trees).
                if ! git config --global --get-all safe.directory 2>/dev/null | grep -qxF "${DIR}"; then
                    git config --global --add safe.directory "${DIR}"
                fi
                cd "${DIR}"
                git checkout "${BRANCH}"
                '''
            }
        }

        stage('Get Default Kernel') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    echo "Default Kernel: ${sh(script: 'uname -r', returnStdout: true).trim()}"
                }
            }
        }

        stage('Build With Patch Kernel') {
            agent { label params.NODE_LABEL }
            when {
                expression {
                    params.RPM_KERNEL_BUILD == 'BUILD_BOTH'
                }
            }
            steps {
                script {
                    buildKernel('with_patch')
                    installKernelFromBuild('with_patch')
                    env.PATCH_KERNEL = getInstalledKernel('with_patch')
                    echo "PATCH_KERNEL (from build) = ${env.PATCH_KERNEL}"
                }
            }
        }

        stage('Build Base Kernel') {
            agent { label params.NODE_LABEL }
            when {
                expression {
                    params.RPM_KERNEL_BUILD == 'BUILD_BOTH'
                }
            }
            steps {
                script {
                    dir(env.DIR) {
                        // BASE_HEAD_SHA_ID is validated as hex by validateBaseHeadShaIdParam();
                        // still inject via env to keep the shell free of unquoted interpolation.
                        withEnv(["_BASE_HEAD_SHA_ID=${params.BASE_HEAD_SHA_ID?.trim() ?: ''}"]) {
                            sh '''
                                set -eu
                                # withEnv drops a variable whose value is empty, so this is UNSET
                                # (not "") on the normal "build from HEAD" path. Normalise it before
                                # any reference so 'set -u' does not abort here.
                                _BASE_HEAD_SHA_ID="${_BASE_HEAD_SHA_ID:-}"
                                ORIGINAL_BRANCH=$(git symbolic-ref --short -q HEAD || true)
                                ORIGINAL_COMMIT=$(git rev-parse HEAD)
                                printf '%s' "${ORIGINAL_BRANCH}" > .original_branch_for_base_build
                                printf '%s' "${ORIGINAL_COMMIT}" > .original_commit_for_base_build

                                if git show-ref --verify --quiet refs/heads/dummy_test_branch; then
                                    git branch -D dummy_test_branch
                                fi
                                git checkout -b dummy_test_branch

                                if [ -n "${_BASE_HEAD_SHA_ID}" ]; then
                                    if ! git cat-file -e "${_BASE_HEAD_SHA_ID}^{commit}" 2>/dev/null; then
                                        echo "SHA ${_BASE_HEAD_SHA_ID} not in local history; deepening..."
                                        git fetch --deepen=200 || git fetch --unshallow || true
                                    fi
                                    if ! git cat-file -e "${_BASE_HEAD_SHA_ID}^{commit}" 2>/dev/null; then
                                        echo "ERROR: BASE_HEAD_SHA_ID=${_BASE_HEAD_SHA_ID} not reachable even after deepening." >&2
                                        exit 1
                                    fi
                                    echo "Resetting dummy_test_branch to ${_BASE_HEAD_SHA_ID}"
                                    git reset --hard "${_BASE_HEAD_SHA_ID}"
                                else
                                    echo "BASE_HEAD_SHA_ID is empty: building base from HEAD (no rollback)."
                                fi
                            '''
                        }
                    }
                    try {
                        buildKernel('base_patch')
                        installKernelFromBuild('base_patch')
                        env.BASE_KERNEL = getInstalledKernel('base_patch')
                        echo "BASE_KERNEL (from build) = ${env.BASE_KERNEL}"
                    } finally {
                        dir(env.DIR) {
                            sh '''
                                set -eu
                                ORIGINAL_BRANCH="$(cat .original_branch_for_base_build 2>/dev/null || true)"
                                ORIGINAL_COMMIT="$(cat .original_commit_for_base_build 2>/dev/null || true)"

                                git reset --hard >/dev/null 2>&1 || true
                                git clean -fd >/dev/null 2>&1 || true

                                if [ -n "${ORIGINAL_BRANCH}" ]; then
                                    git checkout -f "${ORIGINAL_BRANCH}" || git checkout --detach "${ORIGINAL_COMMIT}"
                                elif [ -n "${ORIGINAL_COMMIT}" ]; then
                                    git checkout --detach "${ORIGINAL_COMMIT}"
                                fi

                                git branch -D dummy_test_branch >/dev/null 2>&1 || true
                                rm -f .original_branch_for_base_build .original_commit_for_base_build
                            '''
                        }
                    }
                }
            }
        }

        stage('Resolve PATCH_KERNEL and BASE_KERNEL') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    resolvePatchAndBaseKernels()
                }
            }
        }

        stage('Set default kernel (BASE)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothBasePatchLkp() && !shouldSkipBoot('BASE') }
            }
            steps {
                script {
                    requireBothKernelsForBothMode()
                    setGrubbyDefaultKernel(env.BASE_KERNEL.trim(), "BOTH: reboot into BASE ${env.BASE_KERNEL} ...")
                }
            }
        }

        stage('Reboot and wait (BASE)') {
            agent none
            when {
                expression { needsBothBasePatchLkp() && !shouldSkipBoot('BASE') }
            }
            steps {
                script {
                    rebootAndWaitForNode(env.REBOOT_MESSAGE ?: 'BOTH: rebooting into BASE...')
                }
            }
        }

        stage('Verify kernel (BASE)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothBasePatchLkp() }
            }
            steps {
                script {
                    verifyExpectedKernelRunning('BASE')
                }
            }
        }

        stage('LKP on BASE kernel') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothBasePatchLkp() }
            }
            steps {
                script {
                    Lkp_test('host')
                }
            }
        }

        stage('Set default kernel (PATCH)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothBasePatchLkp() && !shouldSkipBoot('PATCH') }
            }
            steps {
                script {
                    enableBootForPatchPhase()
                    setGrubbyDefaultKernel(env.PATCH_KERNEL.trim(), "BOTH: reboot into PATCH ${env.PATCH_KERNEL} ...")
                }
            }
        }

        stage('Reboot and wait (PATCH)') {
            agent none
            when {
                expression { needsBothBasePatchLkp() && !shouldSkipBoot('PATCH') }
            }
            steps {
                script {
                    rebootAndWaitForNode(env.REBOOT_MESSAGE ?: 'BOTH: rebooting into PATCH...')
                }
            }
        }

        stage('Verify kernel (PATCH)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothBasePatchLkp() }
            }
            steps {
                script {
                    verifyExpectedKernelRunning('PATCH')
                }
            }
        }

        stage('LKP on PATCH kernel') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothBasePatchLkp() }
            }
            steps {
                script {
                    Lkp_test('host')
                }
            }
        }

        stage('Set default kernel (boot target)') {
            agent { label params.NODE_LABEL }
            when {
                expression {
                    needsBootTargetWorkflow() && bootTargetKernelRole() && !shouldSkipBoot(bootTargetKernelRole())
                }
            }
            steps {
                script {
                    prepareBootTargetGrubby()
                }
            }
        }

        stage('Reboot and wait for node (boot target)') {
            agent none
            when {
                expression {
                    needsBootTargetWorkflow() && bootTargetKernelRole() && !shouldSkipBoot(bootTargetKernelRole())
                }
            }
            steps {
                script {
                    rebootAndWaitForNode(env.REBOOT_MESSAGE ?: 'Rebooting...')
                }
            }
        }

        stage('Verify kernel after boot') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBootTargetWorkflow() && bootTargetKernelRole() }
            }
            steps {
                script {
                    verifyExpectedKernelRunning(bootTargetKernelRole())
                }
            }
        }

        stage('LKP after boot') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsLkpAfterBoot() }
            }
            steps {
                script {
                    Lkp_test('host')
                }
            }
        }

        stage('Single-kernel VM run: create -> LKP -> delete') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsSingleKernelVmWorkflow() }
            }
            steps {
                script {
                    def runType = vmRunTypeForBootOption(params.LKP_RUN_OPTIONS)
                    vmLkpRun(runType, lkpReportContextFromVmRunType(runType))
                }
            }
        }

        stage('BOTH_VM: Set default kernel (BASE)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothKernelVmWorkflow() && !shouldSkipBoot('BASE') }
            }
            steps {
                script {
                    requireBothKernelsForBothMode()
                    setGrubbyDefaultKernel(env.BASE_KERNEL.trim(), "BOTH_VM: reboot into BASE ${env.BASE_KERNEL} ...")
                }
            }
        }

        stage('BOTH_VM: Reboot and wait (BASE)') {
            agent none
            when {
                expression { needsBothKernelVmWorkflow() && !shouldSkipBoot('BASE') }
            }
            steps {
                script {
                    rebootAndWaitForNode(env.REBOOT_MESSAGE ?: 'BOTH_VM: rebooting into BASE...')
                }
            }
        }

        stage('BOTH_VM: Verify kernel (BASE)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothKernelVmWorkflow() }
            }
            steps {
                script {
                    verifyExpectedKernelRunning('BASE')
                }
            }
        }

        stage('BOTH_VM: VM run on BASE kernel (create -> LKP -> delete)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothKernelVmWorkflow() }
            }
            steps {
                script {
                    // Self-contained: create the stress VMs, run LKP, then delete them — so the
                    // VMs do NOT persist across the PATCH reboot. The PATCH phase creates a fresh
                    // set. This makes every stage independently re-runnable: resuming any stage in
                    // a later run recreates exactly the VMs it needs (matches the ALL_* flow).
                    def runType = vmRunTypeForBootOption(params.LKP_RUN_OPTIONS)
                    vmLkpRun(runType, lkpReportContextFromVmRunType(runType))
                }
            }
        }

        stage('BOTH_VM: Set default kernel (PATCH)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothKernelVmWorkflow() && !shouldSkipBoot('PATCH') }
            }
            steps {
                script {
                    enableBootForPatchPhase()
                    setGrubbyDefaultKernel(env.PATCH_KERNEL.trim(), "BOTH_VM: reboot into PATCH ${env.PATCH_KERNEL} ...")
                }
            }
        }

        stage('BOTH_VM: Reboot and wait (PATCH)') {
            agent none
            when {
                expression { needsBothKernelVmWorkflow() && !shouldSkipBoot('PATCH') }
            }
            steps {
                script {
                    rebootAndWaitForNode(env.REBOOT_MESSAGE ?: 'BOTH_VM: rebooting into PATCH...')
                }
            }
        }

        stage('BOTH_VM: Verify kernel (PATCH)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothKernelVmWorkflow() }
            }
            steps {
                script {
                    verifyExpectedKernelRunning('PATCH')
                }
            }
        }

        stage('BOTH_VM: VM run on PATCH kernel (create -> LKP -> delete)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsBothKernelVmWorkflow() }
            }
            steps {
                script {
                    def runType = vmRunTypeForBootOption(params.LKP_RUN_OPTIONS)
                    vmLkpRun(runType, lkpReportContextFromVmRunType(runType))
                }
            }
        }

        stage('ALL: Set default kernel (BASE)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequenceBase() && !shouldSkipBoot('BASE') }
            }
            steps {
                script {
                    requireKernel(env.BASE_KERNEL, 'BASE_KERNEL')
                    setGrubbyDefaultKernel(env.BASE_KERNEL.trim(), "ALL: reboot into BASE ${env.BASE_KERNEL} ...")
                }
            }
        }

        stage('ALL: Reboot and wait (BASE)') {
            agent none
            when {
                expression { needsAllSequenceBase() && !shouldSkipBoot('BASE') }
            }
            steps {
                script {
                    rebootAndWaitForNode(env.REBOOT_MESSAGE ?: 'ALL: rebooting into BASE...')
                }
            }
        }

        stage('ALL: Verify kernel (BASE)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequenceBase() }
            }
            steps {
                script {
                    verifyExpectedKernelRunning('BASE')
                }
            }
        }

        stage('Host with base kernel') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequenceBase() }
            }
            steps {
                script {
                    hostLkpThenCleanup()
                }
            }
        }

        stage('Host + Guests with base kernel [ LKP only on Host ]') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequenceBase() }
            }
            steps {
                script {
                    vmLkpRun('WITHOUT_LKP', 'vm_no_lkp_image')
                }
            }
        }

        stage('Host + Guests with base kernel [ LKP on both Host & Guests ]') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequenceBase() }
            }
            steps {
                script {
                    vmLkpRun('WITH_LKP', 'vm_lkp_image')
                }
            }
        }

        stage('ALL: Set default kernel (PATCH)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequencePatch() && !shouldSkipBoot('PATCH') }
            }
            steps {
                script {
                    requireKernel(env.PATCH_KERNEL, 'PATCH_KERNEL')
                    enableBootForPatchPhase()
                    setGrubbyDefaultKernel(env.PATCH_KERNEL.trim(), "ALL: reboot into PATCH ${env.PATCH_KERNEL} ...")
                }
            }
        }

        stage('ALL: Reboot and wait (PATCH)') {
            agent none
            when {
                expression { needsAllSequencePatch() && !shouldSkipBoot('PATCH') }
            }
            steps {
                script {
                    rebootAndWaitForNode(env.REBOOT_MESSAGE ?: 'ALL: rebooting into PATCH...')
                }
            }
        }

        stage('ALL: Verify kernel (PATCH)') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequencePatch() }
            }
            steps {
                script {
                    verifyExpectedKernelRunning('PATCH')
                }
            }
        }

        stage('Host with patched kernel') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequencePatch() }
            }
            steps {
                script {
                    hostLkpThenCleanup()
                }
            }
        }

        stage('Host + Guests with patched kernel [ LKP only on Host ]') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequencePatch() }
            }
            steps {
                script {
                    vmLkpRun('WITHOUT_LKP', 'vm_no_lkp_image')
                }
            }
        }

        stage('Host + Guests with patched kernel [ LKP on both Host & Guests ]') {
            agent { label params.NODE_LABEL }
            when {
                expression { needsAllSequencePatch() }
            }
            steps {
                script {
                    vmLkpRun('WITH_LKP', 'vm_lkp_image')
                }
            }
        }

        stage('Remove LKP-running banner (motd)') {
            agent { label params.NODE_LABEL }
            when {
                expression { params.RPM_KERNEL_BUILD != 'NULL_NO_KERNEL_WORKFLOW' }
            }
            steps {
                script {
                    clearLkpMotdBanner()
                }
            }
        }

    }

    post {
        always {
            script {
                if (params.RPM_KERNEL_BUILD == 'NULL_NO_KERNEL_WORKFLOW') {
                    echo 'NULL_NO_KERNEL_WORKFLOW: nothing was created; skipping post-build VM status check and cleanup.'
                    return
                }
                // A build that failed because the SUT never came back would otherwise wait
                // here forever: node() queues until the agent is online, with no deadline
                // of its own.
                try {
                    timeout(time: 2, unit: 'MINUTES') {
                        node(params.NODE_LABEL) {
                            sh '''
                            echo ""
                            echo "======================================="
                            echo "Current VM Status"
                            echo "======================================="
                            virsh list --all || true
                            '''
                        }
                    }
                } catch (Throwable t) {
                    echo "Post-build VM status check failed (non-fatal): ${t}"
                }

                // Final safety-net cleanup: delete any leftover stress VM families so a
                // half-finished run never leaves VMs behind. Hard 1-minute budget so this
                // can never hang the build; best-effort, so failures/timeouts are non-fatal.
                try {
                    timeout(time: 1, unit: 'MINUTES') {
                        node(params.NODE_LABEL) {
                            echo 'Post-build cleanup: deleting all *_stressVM guests (1 min budget).'
                            deleteAllStressVmFamilies()
                        }
                    }
                } catch (Throwable t) {
                    echo "Post-build VM deletion failed or timed out (non-fatal): ${t}"
                }

                // Kill any leftover HOST-side LKP workloads (hackbench/ebizzy/unixbench and
                // their monitors) so an aborted or failed run does not leave processes loading
                // the box and skewing the next run. Hard 2-minute budget; best-effort/non-fatal.
                try {
                    timeout(time: 2, unit: 'MINUTES') {
                        node(params.NODE_LABEL) {
                            echo 'Post-build cleanup: killing leftover host LKP workloads (2 min budget).'
                            killHostLkpWorkloads()
                        }
                    }
                } catch (Throwable t) {
                    echo "Post-build host LKP kill failed or timed out (non-fatal): ${t}"
                }

                // Safety net: a failed/aborted run never reaches the final banner stage, so
                // restore /etc/motd here too. Best-effort, 1-minute budget, non-fatal.
                try {
                    timeout(time: 1, unit: 'MINUTES') {
                        node(params.NODE_LABEL) {
                            clearLkpMotdBanner()
                        }
                    }
                } catch (Throwable t) {
                    echo "Post-build motd restore failed or timed out (non-fatal): ${t}"
                }
            }
        }
        aborted {
            script {
                if (params.RPM_KERNEL_BUILD == 'NULL_NO_KERNEL_WORKFLOW') {
                    return
                }
                try {
                    timeout(time: 3, unit: 'MINUTES') {
                        node(params.NODE_LABEL) {
                            echo 'Pipeline aborted: deleting all *_stressVM guests (6 per-OS + extras).'
                            deleteAllStressVmFamilies()
                        }
                    }
                } catch (Throwable t) {
                    echo "Abort-time VM cleanup failed (non-fatal): ${t}"
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Validation & resolution
// ---------------------------------------------------------------------------

/**
 * Record the SUT's own IP in env.NODE_IP, for the log: when a box does not come back from a
 * reboot, the build log is where you look for the address to reach it on.
 */
def resolveNodeIpFromAgent() {
    if (!params.NODE_LABEL?.trim()) {
        error('NODE_LABEL must not be empty.')
    }
    // First IPv4 from `hostname -I`; loopback and link-local are already excluded by it.
    def ip = sh(script: 'set -eu; hostname -I | awk \'{print $1}\'', returnStdout: true).trim()
    if (!ip) {
        error("No non-loopback IPv4 from 'hostname -I' on agent ${env.NODE_NAME ?: params.NODE_LABEL}.")
    }
    env.NODE_IP = ip
    echo "SUT ${env.NODE_NAME ?: params.NODE_LABEL} is ${ip}"
}

/** Run from an agent stage: fail if path is missing (avoids shell metacharacters in path via env). */
def requireAgentPathExists(String path, String paramName, boolean mustBeDirectory) {
    if (!path?.trim()) {
        return
    }
    withEnv([
        "_VALID_PATH=${path.trim()}",
        "_PARAM_NAME=${paramName}",
        "_DIR_ONLY=${mustBeDirectory ? '1' : '0'}"
    ]) {
        sh '''
        set -eu
        if [ "$_DIR_ONLY" = 1 ]; then
            if [ ! -d "$_VALID_PATH" ]; then
                echo "$_PARAM_NAME: not a directory or missing on agent: $_VALID_PATH" >&2
                exit 1
            fi
        else
            if [ ! -e "$_VALID_PATH" ]; then
                echo "$_PARAM_NAME: path does not exist on agent: $_VALID_PATH" >&2
                exit 1
            fi
        fi
        '''
    }
}

def requirePresetGitCheckoutOnAgent(String path) {
    if (!path?.trim()) {
        return
    }
    withEnv(["_R=${path.trim()}"]) {
        sh '''
        set -eu
        if [ ! -d "$_R/.git" ]; then
            echo "EXISTING_REPO_PATH: expected a git checkout (.git missing under $_R)" >&2
            exit 1
        fi
        '''
    }
}

/**
 * Installs only what this run actually needs, chosen per distro (env.BOOT_METHOD,
 * set by 'Detect OS / boot method') and per RPM_KERNEL_BUILD:
 *  - LKP toolchain (pyfiglet, used by LKP_Automated's `make`) whenever LKP may run,
 *    i.e. every mode except NULL_NO_KERNEL_WORKFLOW.
 *  - Kernel build toolchain (compiler, packaging tools, headers) ONLY when
 *    RPM_KERNEL_BUILD=BUILD_BOTH actually compiles a kernel from source.
 *    MANUAL_BOTH_NO_BUILD never builds, so it skips that heavier set.
 */
def installPrerequisites() {
    boolean needsBuildToolchain = params.RPM_KERNEL_BUILD == 'BUILD_BOTH'

    withEnv(["_BUILD=${needsBuildToolchain ? '1' : '0'}"]) {
    if (env.BOOT_METHOD == 'grub_debian') {
            sh '''
            set -eu
            apt-get update -y
            apt-get install -y python3-pyfiglet python3-openpyxl
            if [ "${_BUILD}" = 1 ]; then
            apt-get install -y git make fakeroot build-essential ncurses-dev xz-utils libssl-dev bc flex libelf-dev bison
            fi
            '''
    } else {
        sh '''
        set -eu
            # A plain if, not `command -v dnf && PKG_MGR=dnf`: the latter aborts the whole
            # step under `set -e` on a host that only has yum.
        PKG_MGR=yum
        if command -v dnf >/dev/null 2>&1; then
            PKG_MGR=dnf
        fi

            # Anolis/OpenCloud/OpenEuler ship no python3-pyfiglet, so it comes from pip.
        "$PKG_MGR" install -y python3-pip
        python3 -m pip install pyfiglet

            if [ "${_BUILD}" = 1 ]; then
            "$PKG_MGR" install -y elfutils-devel bc dwarves libvirt gcc git vim time gcc-c++ kernel-devel perl make numactl openssl openssl-devel libmpc mpfr ncurses-devel bison tar rsync libstdc++-devel libtool flex zlib zlib-devel elfutils-libelf-devel createrepo rpm-build rpmdevtools
            fi
            '''
        }
    }
}

def validateBaseHeadShaIdParam() {
    def sha = params.BASE_HEAD_SHA_ID?.trim()
    if (!sha) {
        // Empty is allowed: means "don't reset, build base from HEAD."
        return
    }
    if (!(sha ==~ /^[0-9a-fA-F]{7,40}$/)) {
        error("BASE_HEAD_SHA_ID must be a hex commit SHA (7-40 hex chars). Got: '${sha}'")
    }
}

def validateRpmKernelParams() {
    if (!params.JIRA_ID?.trim()) {
        error('JIRA_ID is required: it names the result directory /home/amd/DEAE_<JIRA_ID> and the LKP result workbook. Provide the Jira ticket number and re-run.')
    }

    if (params.RPM_KERNEL_BUILD == 'BUILD_BOTH') {
        // BASE_HEAD_SHA_ID is only consumed by the Build Base Kernel stage, which only
        // runs for BUILD_BOTH. Validating it for other modes would produce confusing
        // errors about an unused field.
        validateBaseHeadShaIdParam()
        if (!params.EXISTING_REPO_PATH?.trim()) {
            error('EXISTING_REPO_PATH is required when BUILD_BOTH (kernel tree path on agent; no clone).')
        }
        if (!params.EXISTING_REPO_BRANCH?.trim()) {
            error('EXISTING_REPO_BRANCH is required when BUILD_BOTH')
        }
        requireAgentPathExists(params.EXISTING_REPO_PATH.trim(), 'EXISTING_REPO_PATH', true)
        requirePresetGitCheckoutOnAgent(params.EXISTING_REPO_PATH.trim())
    }

    switch (params.RPM_KERNEL_BUILD) {
        case 'MANUAL_BOTH_NO_BUILD':
            // Only the kernel(s) actually exercised by LKP_RUN_OPTIONS are mandatory: a BASE-only
            // run needs MANUAL_BASE_KERNEL, a PATCH-only run needs MANUAL_PATCH_KERNEL, BOTH_*/ALL_RUNS
            // need both. Catch typos up front: the named /boot/vmlinuz-<rel> must exist on the agent.
            if (runNeedsBaseKernel()) {
                if (!params.MANUAL_BASE_KERNEL?.trim()) {
                    error("MANUAL_BASE_KERNEL is required for LKP_RUN_OPTIONS=${params.LKP_RUN_OPTIONS}")
                }
                requireAgentPathExists("/boot/vmlinuz-${params.MANUAL_BASE_KERNEL.trim()}", 'MANUAL_BASE_KERNEL', false)
            }
            if (runNeedsPatchKernel()) {
                if (!params.MANUAL_PATCH_KERNEL?.trim()) {
                    error("MANUAL_PATCH_KERNEL is required for LKP_RUN_OPTIONS=${params.LKP_RUN_OPTIONS}")
                }
                requireAgentPathExists("/boot/vmlinuz-${params.MANUAL_PATCH_KERNEL.trim()}", 'MANUAL_PATCH_KERNEL', false)
            }
            // A BASE+PATCH run must use two DIFFERENT kernel releases: identical names make the
            // booted-kernel role ambiguous (uname -r matches both), which would mis-route the
            // Excel column and backup dir. Fail fast instead of silently mislabeling PATCH as BASE.
            if (runNeedsBaseKernel() && runNeedsPatchKernel() &&
                params.MANUAL_BASE_KERNEL?.trim() && params.MANUAL_PATCH_KERNEL?.trim() &&
                params.MANUAL_BASE_KERNEL.trim() == params.MANUAL_PATCH_KERNEL.trim()) {
                error("MANUAL_BASE_KERNEL and MANUAL_PATCH_KERNEL are identical ('${params.MANUAL_BASE_KERNEL.trim()}'); " +
                    "a BASE+PATCH run (${params.LKP_RUN_OPTIONS}) needs two different kernel releases so their results can be told apart.")
            }
            break
        case 'NULL_NO_KERNEL_WORKFLOW':
            break
        default:
            break
    }

    validateVmCreationParams()
}

def validateVmCreationParams() {
    if (bootOptionRequiresVmCreation() && params.VM_CREATION_REQUIRED != 'YES') {
        error("LKP_RUN_OPTIONS=${params.LKP_RUN_OPTIONS} requires VM_CREATION_REQUIRED=YES")
    }

    if (params.VM_CREATION_REQUIRED != 'YES') {
        return
    }

    // EXTRA_VMS is a 0..5 choice; a run always spawns the 6 per-OS guests, plus this many
    // extra guests cloned from the host's own running OS.
    String ev = params.EXTRA_VMS?.trim()
    if (!ev || !(ev ==~ /^[0-5]$/)) {
        error("EXTRA_VMS must be one of 0,1,2,3,4,5 (got '${params.EXTRA_VMS}').")
    }

    // Checkpoint: every golden image (both families, one per OS) must exist under the
    // hard-coded image dir before any VM run starts, so a missing image fails the build at
    // the start instead of mid-run.
    List<String> allFiles = ((VM_IMAGES_NOLKP.values() as List) + (VM_IMAGES_LKP.values() as List))
    withEnv(["_IMG_DIR=${VM_IMAGE_DIR}", "_FILES=${allFiles.join(' ')}"]) {
        sh '''
        set -eu
        missing=""
        for f in ${_FILES}; do
            [ -f "${_IMG_DIR}/${f}" ] || missing="${missing} ${f}"
        done
        if [ -n "${missing}" ]; then
            echo "Missing golden qcow2 image(s) in ${_IMG_DIR}:${missing}" >&2
            exit 1
        fi
        echo "All golden qcow2 images present in ${_IMG_DIR}."
        '''
    }

    // Extra VMs are cloned from the host's own OS golden, so the host OS must be one we know.
    if (ev.toInteger() > 0) {
        String hostKey = detectHostOsKey()
        if (!hostKey) {
            error("EXTRA_VMS=${ev} requested but the host OS is not one of " +
                "anolis/Rocky/Opencloud/Euler/Velinux/Ubuntu; cannot pick a golden for the extra VMs.")
        }
        echo "Extra VMs (${ev}) will be cloned from the host OS golden: ${hostKey}."
    }
}

/** Golden-image family (OS -> filename) for a VM run type. */
Map vmImagesForRunType(String runType) {
    switch (runType) {
        case 'WITHOUT_LKP':
            return VM_IMAGES_NOLKP
        case 'WITH_LKP':
            return VM_IMAGES_LKP
        default:
            error("Unknown VM runType '${runType}'. Expected WITHOUT_LKP or WITH_LKP.")
    }
}

/**
 * OS key (matching VM_IMAGES_* map keys) for the host's currently-running OS, or '' if the
 * host OS is not one of the six known distributions. Reads /etc/os-release on the agent.
 */
String detectHostOsKey() {
    String id = sh(
        script: '. /etc/os-release 2>/dev/null; echo "${ID:-} ${ID_LIKE:-} ${NAME:-}"',
        returnStdout: true
    ).trim().toLowerCase()
    if (id.contains('anolis'))    { return 'anolis' }
    if (id.contains('rocky'))     { return 'Rocky' }
    if (id.contains('opencloud')) { return 'Opencloud' }
    if (id.contains('euler'))     { return 'Euler' }
    if (id.contains('velinux'))   { return 'Velinux' }
    if (id.contains('ubuntu'))    { return 'Ubuntu' }
    return ''
}

String vmRunTypeForBootOption(String bootOption) {
    switch (bootOption) {
        case 'PATCH_KERNEL_NOLKP_IN_VM':
        case 'BASE_KERNEL_NOLKP_IN_VM':
        case 'BOTH_KERNELS_NOLKP_IN_VM':
            return 'WITHOUT_LKP'
        case 'PATCH_KERNEL_LKP_IN_VM':
        case 'BASE_KERNEL_LKP_IN_VM':
        case 'BOTH_KERNELS_LKP_IN_VM':
            return 'WITH_LKP'
        default:
            return ''
    }
}

/** LKP report bucket for stages that used createVms(WITHOUT_LKP|WITH_LKP). */
String lkpReportContextFromVmRunType(String runType) {
    return runType == 'WITHOUT_LKP' ? 'vm_no_lkp_image' : 'vm_lkp_image'
}

/**
 * Excel data column for this LKP run in the template: chosen by report context + booted kernel
 * role (see the Elves-style workbook layout). Returns '' if it cannot be determined (then the
 * report writes headers/labels only, no data).
 *   host            + BASE/PATCH -> C / D   (Scenario A: host, no VMs)
 *   vm_no_lkp_image + BASE/PATCH -> E / F   (Scenario B: host + VMs, load on host)
 *   vm_lkp_image    + BASE/PATCH -> G / H   (Scenario C: host + VMs, load on host + guests)
 */
String excelColumnForRun(String lkpReportContext) {
    String role = currentKernelRoleLabel()   // BASE / PATCH / CURRENT
    Map cols = [
        'host'           : [BASE: 'C', PATCH: 'D'],
        'vm_no_lkp_image': [BASE: 'E', PATCH: 'F'],
        'vm_lkp_image'   : [BASE: 'G', PATCH: 'H'],
    ]
    return (cols[lkpReportContext ?: 'host'] ?: [:])[role] ?: ''
}

/** LKP_CONTEXTS entry for a context string, defaulting to the bare-metal host run. */
Map lkpContext(String lkpReportContext) {
    return LKP_CONTEXTS[lkpReportContext ?: 'host'] ?: LKP_CONTEXTS.host
}

/**
 * Create <PIPELINE_DATA_DIR>/<JOB_NAME>/Run_<BUILD_NUMBER> once per build and publish it as
 * env.RUN_BACKUP_DIR for every later LKP backup. The job's folder path + name comes from
 * env.JOB_NAME (the Jenkins folder tree, spaces replaced by '_'). The archive ROOT must
 * already exist (fails fast if not); only the per-build subdirs under it are created here.
 */
def prepareRunBackupDir() {
    String root = PIPELINE_DATA_DIR.endsWith('/') ? PIPELINE_DATA_DIR : "${PIPELINE_DATA_DIR}/"
    // Jenkins job folder path + name; spaces -> '_' so the on-disk path has no spaces.
    String jobPath = (env.JOB_NAME ?: env.JOB_BASE_NAME ?: 'lkp').trim().replace(' ', '_')
    String target = "${root}${jobPath}/Run_${env.BUILD_NUMBER}"
    withEnv(["_DATA_ROOT=${root}", "_BUILD_DIR=${target}"]) {
        sh '''
        set -eu
        # The archive ROOT must already exist (it is normally a dedicated mount). Fail here
        # rather than let `mkdir -p` scatter a stray tree onto the agent's local disk.
        if [ ! -d "${_DATA_ROOT}" ]; then
            echo "Result-archive root does not exist on this agent: ${_DATA_ROOT}" >&2
            echo "Create it (or fix PIPELINE_DATA_DIR at the top of the pipeline) and re-run." >&2
            exit 1
        fi
        # Per-build subdirs are created under the existing root; -p also avoids a race when
        # two jobs share the root. Results land under the Jenkins job's own folder path.
        mkdir -p "${_BUILD_DIR}"
        echo "LKP result backups for this build: ${_BUILD_DIR}"
        '''
    }
    env.RUN_BACKUP_DIR = target
}

/**
 * BASE / PATCH of the kernel currently booted, derived from uname -r so no call site has
 * to pass it. A kernel matching neither (nothing resolved yet) reports CURRENT.
 */
String currentKernelRoleLabel() {
    String running = sh(script: 'uname -r', returnStdout: true).trim()
    if (env.BASE_KERNEL?.trim() && running == env.BASE_KERNEL.trim()) {
        return 'BASE'
    }
    if (env.PATCH_KERNEL?.trim() && running == env.PATCH_KERNEL.trim()) {
        return 'PATCH'
    }
    return 'CURRENT'
}

/** Backup dir name for one LKP run, e.g. BASE_ONLY_LKP, PATCH_VM_LKP. */
String lkpRunBackupName(String lkpReportContext) {
    return "${currentKernelRoleLabel()}_${lkpContext(lkpReportContext).suffix}"
}

/**
 * Archive one finished LKP run: the whole /lkp/result tree (benchmark dirs + result.sh).
 * Runs at the END of Lkp_test so the data is safe before the next run's
 * `rm -rf /lkp/result/<workload>/*` wipes it.
 */
def backupLkpResults(String lkpReportContext) {
    if (!env.RUN_BACKUP_DIR?.trim()) {
        echo 'RUN_BACKUP_DIR is not set (Prepare result backup dir stage did not run); skipping LKP result backup.'
        return
    }
    String name = lkpRunBackupName(lkpReportContext)
    withEnv(["_DEST=${env.RUN_BACKUP_DIR}/${name}"]) {
        sh '''
        set -eu
        mkdir -p "${_DEST}"

        if [ -d /lkp/result ]; then
            # Replace rather than merge, so a re-run of the same stage in one build does
            # not leave a mix of old and new result dirs behind.
            rm -rf "${_DEST}/lkp_result"
            cp -a /lkp/result "${_DEST}/lkp_result"
        else
            echo "WARNING: /lkp/result is missing; nothing to archive for ${_DEST}" >&2
        fi

        echo "Backed up LKP run to ${_DEST}"
        ls -l "${_DEST}"
        '''
    }
}

/** Package type matching the host distro family: deb on Debian/veLinux (grub_debian), else rpm. */
String hostPkgType() {
    return env.BOOT_METHOD == 'grub_debian' ? 'deb' : 'rpm'
}

/**
 * Kernel package to install inside the current-OS guest for this VM run, or '' if none applies:
 *   - BUILD_BOTH        : the package THIS run built and saved under JENKINS_RPM_DIR
 *                         (role BASE -> base_patch, PATCH -> with_patch).
 *   - MANUAL_BOTH_NO_BUILD: the MANUAL_<role>_<type>_PATH parameter for the host package type.
 * role is BASE / PATCH (from the currently booted kernel); anything else yields ''.
 */
String resolveVmPackagePath(String role) {
    String type = hostPkgType()
    if (params.RPM_KERNEL_BUILD == 'BUILD_BOTH') {
        String prefix = (role == 'PATCH') ? 'with_patch' : (role == 'BASE' ? 'base_patch' : '')
        return prefix ? (BUILT_PKG_PATHS[prefix] ?: '') : ''
    }
    if (params.RPM_KERNEL_BUILD == 'MANUAL_BOTH_NO_BUILD') {
        if (role == 'BASE')  { return ((type == 'deb' ? params.MANUAL_BASE_DEB_PATH  : params.MANUAL_BASE_RPM_PATH)  ?: '').trim() }
        if (role == 'PATCH') { return ((type == 'deb' ? params.MANUAL_PATCH_DEB_PATH : params.MANUAL_PATCH_RPM_PATH) ?: '').trim() }
    }
    return ''
}

/**
 * Copy the kernel package into the guest whose OS matches the host (<hostOS>_stressVM), install
 * it, set it default and reboot the guest, then wait for it to return on the new kernel. Only the
 * current-OS guest is touched; the other 5 per-OS guests and the EXTRA_VMS clones are left as
 * spawned. The package (base or patch) is chosen from the CURRENTLY BOOTED kernel role, so a BASE
 * VM run installs the base package and a PATCH VM run installs the patch package. No-op (with a
 * message) when the host OS is unknown or no package is resolvable (e.g. NULL_NO_KERNEL_WORKFLOW).
 * Modelled on Rollback_jenkins/LKP/Rpm_vm_install.groovy.
 */
def installPkgIntoCurrentOsGuest() {
    if (params.VM_CREATION_REQUIRED != 'YES') {
        return
    }
    String hostOs = detectHostOsKey()
    if (!hostOs) {
        echo 'Skipping in-guest kernel install: host OS is not one of the six known distributions.'
        return
    }
    String guest   = "${hostOs}_stressVM"
    String role    = currentKernelRoleLabel()   // BASE / PATCH / CURRENT
    String pkgType = hostPkgType()
    String pkgPath = resolveVmPackagePath(role)
    if (!pkgPath?.trim()) {
        if (params.RPM_KERNEL_BUILD == 'MANUAL_BOTH_NO_BUILD') {
            echo "MANUAL_BOTH_NO_BUILD with no ${pkgType} package provided for role ${role} " +
                 "(MANUAL_*_${pkgType == 'deb' ? 'DEB' : 'RPM'}_PATH empty): skipping the in-guest kernel install. " +
                 "All spawned VMs (6 per-OS + EXTRA_VMS) run their default golden kernel."
        } else {
            echo "Skipping in-guest kernel install for ${guest}: no ${pkgType} package resolved for role ${role} (RPM_KERNEL_BUILD=${params.RPM_KERNEL_BUILD})."
        }
        return
    }

    echo "Installing ${pkgType} package into current-OS guest ${guest} (role ${role}): ${pkgPath}"
    withCredentials([usernamePassword(
        credentialsId: params.VM_LOGIN_CREDENTIALS_ID,
        usernameVariable: 'VM_SSH_USER',
        passwordVariable: 'VM_SSH_PASS'
    )]) {
        withEnv([
            "_VM=${guest}",
            "_PKG=${pkgPath}",
            "_PKG_TYPE=${pkgType}",
            "_WAIT=300",
            "LIBVIRT_DEFAULT_URI=qemu:///system"
        ]) {
            sh '''
            set -eu
            SSHOPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
            export SSHPASS="${VM_SSH_PASS}"

            [ -f "${_PKG}" ] || { echo "Package not found on agent: ${_PKG}" >&2; exit 1; }

            if ! command -v sshpass >/dev/null 2>&1; then
                echo "sshpass not found; attempting install..."
                (dnf install -y sshpass || yum install -y sshpass || { apt-get update && apt-get install -y sshpass; }) \
                    || { echo "Could not install sshpass on the agent." >&2; exit 1; }
            fi

            # Resolve the guest IP from its DHCP lease (match the VM's MAC).
            MAC=$(virsh domiflist "${_VM}" | awk '/network/ {print $5; exit}')
            echo "Guest ${_VM} MAC: ${MAC}"
            IP=""; i=0
            while [ "$i" -lt "${_WAIT}" ]; do
                IP=$(virsh net-dhcp-leases default 2>/dev/null \
                     | awk -v m="${MAC}" 'tolower($0) ~ tolower(m) {print $5}' \
                     | cut -d/ -f1 | head -1)
                [ -n "${IP}" ] && break
                sleep 5; i=$((i + 5))
            done
            [ -n "${IP}" ] || { echo "No DHCP lease for ${_VM} within ${_WAIT}s" >&2; exit 1; }
            echo "Guest IP: ${IP}"

            SSH="sshpass -e ssh ${SSHOPTS} ${VM_SSH_USER}@${IP}"
            SCP="sshpass -e scp ${SSHOPTS}"

            # Wait for SSH to accept the login.
            i=0
            while [ "$i" -lt "${_WAIT}" ]; do
                if ${SSH} true 2>/dev/null; then break; fi
                sleep 5; i=$((i + 5))
            done
            ${SSH} 'echo guest: $(hostname) $(uname -r)' || { echo "SSH never became usable within ${_WAIT}s" >&2; exit 1; }

            # Idle the guest (stop LKP autorun; transient, stays enabled) and free space on the
            # disposable overlay before installing.
            ${SSH} 'sudo systemctl stop auto_lkp.service' || true
            ${SSH} "sudo pkill -f 'lkp run' ; sudo pkill -f hackbench ; sudo pkill -f ebizzy ; sudo pkill -f unixbench" || true
            ${SSH} 'sudo rm -rf /var/lib/lkp-tests/tmp-pkg/* /lkp/*/tmp-pkg/* 2>/dev/null; (command -v dnf >/dev/null 2>&1 && sudo dnf clean all) 2>/dev/null; (command -v apt-get >/dev/null 2>&1 && sudo apt-get clean) 2>/dev/null; sudo journalctl --vacuum-time=1d 2>/dev/null; true' || true

            if [ "${_PKG_TYPE}" = rpm ]; then DEST=/tmp/kernel_test.rpm; else DEST=/tmp/kernel_test.deb; fi
            echo "Copying ${_PKG_TYPE} package into ${_VM} (${DEST})"
            ${SCP} "${_PKG}" "${VM_SSH_USER}@${IP}:${DEST}"

            echo "Installing package + setting default kernel in ${_VM}"
            ${SSH} "PKG_TYPE='${_PKG_TYPE}' DEST='${DEST}' bash -s" <<'GUEST'
set -eu
PKG_TYPE="${PKG_TYPE:?}"; DEST="${DEST:?}"
if [ "$(id -u)" -eq 0 ]; then SUDO=""; else SUDO="sudo"; fi

if [ "$PKG_TYPE" = rpm ]; then
    KREL=$(rpm -qlp "$DEST" 2>/dev/null | sed -n 's#^/boot/vmlinuz-##p' | head -1)
    $SUDO dnf -y --disablerepo="*" install "$DEST" || $SUDO rpm -ivh --force "$DEST"
else
    KREL=$(dpkg-deb -c "$DEST" 2>/dev/null | grep -oE '/boot/vmlinuz-[^[:space:]]+' | head -1 | sed 's#.*/boot/vmlinuz-##')
    $SUDO apt-get install -y "$DEST" 2>/dev/null || { $SUDO dpkg -i "$DEST" || $SUDO apt-get -y -f install; }
fi
echo "Guest installs kernel: ${KREL:-<unknown>}"
printf '%s' "${KREL}" | $SUDO tee /tmp/kernel_test.krel >/dev/null

if command -v grubby >/dev/null 2>&1; then
    if [ -n "$KREL" ] && [ -e "/boot/vmlinuz-$KREL" ]; then
        $SUDO grubby --set-default="/boot/vmlinuz-$KREL" || true
    fi
    $SUDO grubby --default-kernel || true
else
    if ! grep -q '^GRUB_DEFAULT=saved' /etc/default/grub 2>/dev/null; then
        if grep -q '^GRUB_DEFAULT=' /etc/default/grub 2>/dev/null; then
            $SUDO sed -i 's/^GRUB_DEFAULT=.*/GRUB_DEFAULT=saved/' /etc/default/grub
        else
            echo 'GRUB_DEFAULT=saved' | $SUDO tee -a /etc/default/grub >/dev/null
        fi
    fi
    GRUB_CFG=/boot/grub/grub.cfg
    [ -f "$GRUB_CFG" ] || GRUB_CFG=/boot/grub2/grub.cfg
    if command -v update-grub >/dev/null 2>&1; then $SUDO update-grub;
    elif command -v grub-mkconfig >/dev/null 2>&1; then $SUDO grub-mkconfig -o "$GRUB_CFG"; fi
    if [ -n "$KREL" ] && [ -f "$GRUB_CFG" ]; then
        SUBMENU=$(grep -E "submenu '.*Advanced options" "$GRUB_CFG" | head -1 | sed -n "s/.*submenu '\\([^']*\\)'.*/\\1/p")
        ENTRY=$(grep -F "with Linux $KREL" "$GRUB_CFG" | grep menuentry | head -1 | sed -n "s/.*menuentry '\\([^']*\\)'.*/\\1/p")
        if [ -n "$ENTRY" ]; then
            if [ -n "$SUBMENU" ]; then $SUDO grub-set-default "${SUBMENU}>${ENTRY}"; else $SUDO grub-set-default "$ENTRY"; fi
        fi
    fi
fi

$SUDO systemd-run --on-active=2 systemctl reboot 2>/dev/null || $SUDO sh -c 'nohup reboot >/dev/null 2>&1 &' || true
echo "Guest reboot triggered."
GUEST

            # Wait for the guest to come back after the install reboot, then just print its
            # running kernel and continue. We deliberately do NOT verify the booted kernel or
            # fail the pipeline here -- a mismatch/timeout only prints a note.
            KREL=$(${SSH} 'cat /tmp/kernel_test.krel 2>/dev/null' 2>/dev/null || true)
            echo "Waiting for ${_VM} to come back after reboot (installed: ${KREL:-unknown}) ..."
            sleep 20
            i=0; RUNNING=""
            while [ "$i" -lt "${_WAIT}" ]; do
                RUNNING=$(${SSH} 'uname -r' 2>/dev/null || true)
                [ -n "${RUNNING}" ] && break
                sleep 10; i=$((i + 10))
            done
            if [ -n "${RUNNING}" ]; then
                echo "${_VM} current kernel: ${RUNNING}"
            else
                echo "NOTE: ${_VM} did not respond within ${_WAIT}s after reboot; continuing anyway." >&2
            fi
            '''
        }
    }
}

/**
 * Creates the stress-VM guests for a run:
 *   - one guest per OS (<OS>_stressVM) cloned from the golden family matching runType, and
 *   - EXTRA_VMS extra guests (<hostOS>_stressVM_e1 ..) cloned from the host's own OS golden.
 * runType: WITHOUT_LKP (no-LKP goldens) | WITH_LKP (LKP goldens).
 * Each guest is a fresh disposable qcow2 overlay on the golden; the golden is never modified.
 * After spawning, the guest whose OS matches the host gets the built/manual kernel package
 * installed and booted (installPkgIntoCurrentOsGuest); the other guests are left as spawned.
 */
def createVms(String runType, String vmPrefix = null) {
    if (params.VM_CREATION_REQUIRED != 'YES') {
        echo 'VM creation skipped: VM_CREATION_REQUIRED=NO'
        return
    }

    Map images = vmImagesForRunType(runType)
    // "OS:filename" pairs (no spaces in either token) for the 6 per-OS guests.
    String pairs = ''
    for (e in images) {
        pairs = pairs ? "${pairs} ${e.key}:${e.value}" : "${e.key}:${e.value}"
    }

    int extra = (params.EXTRA_VMS?.trim() ?: '0').toInteger()
    String hostOs = ''
    String hostFile = ''
    if (extra > 0) {
        hostOs = detectHostOsKey()
        if (!hostOs) {
            error("EXTRA_VMS=${extra} requested but host OS is not one of the six known distributions.")
        }
        hostFile = images[hostOs]
    }

    echo "Creating VMs (runType=${runType}): 6 per-OS guests + ${extra} extra from host OS '${hostOs ?: 'n/a'}'."

    withEnv([
        "_IMG_DIR=${VM_IMAGE_DIR}",
        "_PAIRS=${pairs}",
        "_EXTRA=${extra}",
        "_HOST_OS=${hostOs}",
        "_HOST_FILE=${hostFile}",
        "LIBVIRT_DEFAULT_URI=qemu:///system"
    ]) {
        sh '''
        set -eu

        IMG_DIR="${_IMG_DIR}"
        mkdir -p "${IMG_DIR}"
        echo "Overlay disks will be created in: ${IMG_DIR}"

        # Create one guest: $1 = VM name, $2 = golden filename (in IMG_DIR).
        spawn_one() {
            VM_NAME="$1"
            BASE="${IMG_DIR}/$2"
            VM_DISK="${IMG_DIR}/${VM_NAME}.qcow2"

            if [ ! -f "${BASE}" ]; then
                echo "Golden qcow2 image not found: ${BASE}" >&2
                exit 1
            fi

            echo "===== Creating VM: ${VM_NAME} (golden: $2) ====="

            if virsh dominfo "${VM_NAME}" >/dev/null 2>&1; then
                echo "${VM_NAME} already exists; removing it first."
                virsh destroy "${VM_NAME}" >/dev/null 2>&1 || true
                virsh undefine "${VM_NAME}" --nvram >/dev/null 2>&1 \
                    || virsh undefine "${VM_NAME}" >/dev/null 2>&1 || true
            fi
            rm -f "${VM_DISK}"

            # The qemu process must TRAVERSE the image dir and READ the backing golden
            # (libvirt only relabels the top overlay, not the backing file or its directory).
            # That user is 'qemu' on RHEL-family but 'libvirt-qemu' (a different uid) on
            # Debian/veLinux, where the chown below is a no-op. So chown to qemu where it
            # exists, and in ALL cases make the golden world-readable + the dir traversable
            # (RO backing, safe) so any qemu uid can open it -- otherwise the guest fails with
            # "Cannot access storage file ... Permission denied".
            if id qemu >/dev/null 2>&1; then
                chown qemu:qemu "${BASE}" 2>/dev/null || true
            fi
            chmod 0644 "${BASE}" 2>/dev/null || true
            chmod o+rx "${IMG_DIR}" 2>/dev/null || true

            # Fresh qcow2 overlay on top of the golden: the stress VM is disposable and the
            # golden is never modified (deleting the overlay reclaims all its space).
            qemu-img create -f qcow2 -b "${BASE}" -F qcow2 "${VM_DISK}"
            if id qemu >/dev/null 2>&1; then
                chown qemu:qemu "${VM_DISK}" 2>/dev/null || true
                chmod 0660 "${VM_DISK}" 2>/dev/null || true
            fi

            # virt-install auto-detects the QEMU emulator per distro; --import boots the
            # existing overlay disk (no OS install); --noautoconsole returns immediately.
            virt-install \
                --name "${VM_NAME}" \
                --memory 33000 \
                --vcpus 16 \
                --cpu host \
                --disk path="${VM_DISK}",format=qcow2,bus=virtio \
                --import \
                --network network=default,model=virtio \
                --graphics vnc \
                --video vga \
                --osinfo detect=on,require=off \
                --noautoconsole

            echo "${VM_NAME} created successfully"
        }

        # 6 per-OS guests from the selected golden family.
        for pair in ${_PAIRS}; do
            OS=${pair%%:*}
            FILE=${pair#*:}
            spawn_one "${OS}_stressVM" "${FILE}"
        done

        # Extra guests cloned from the host's own running-OS golden.
        i=1
        while [ "${i}" -le "${_EXTRA}" ]; do
            spawn_one "${_HOST_OS}_stressVM_e${i}" "${_HOST_FILE}"
            i=$((i + 1))
        done
        '''
    }

    // Install the built/manual kernel package into the current-OS guest and boot it. The other
    // per-OS guests and the extra clones are left running their golden kernel.
    installPkgIntoCurrentOsGuest()
}

/**
 * Removes every stress-VM guest (<OS>_stressVM and <hostOS>_stressVM_e<n>) and its overlay
 * disk. A run only uses one golden family at a time and always cleans up afterwards, so the
 * single naming scheme lets one pass handle both no-LKP and LKP runs (and any leftovers from
 * an aborted run). Overlays live in VM_IMAGE_DIR beside the goldens; goldens are left intact.
 */
def deleteAllStressVmFamilies() {
    if (params.VM_CREATION_REQUIRED != 'YES') {
        echo 'deleteAllStressVmFamilies: skipped (VM_CREATION_REQUIRED!=YES)'
        return
    }
    withEnv(["_IMG_DIR=${VM_IMAGE_DIR}", "LIBVIRT_DEFAULT_URI=qemu:///system"]) {
        sh '''
        set +e
        IMG_DIR="${_IMG_DIR}"

        # Match <OS>_stressVM and <OS>_stressVM_e<n> domains by name.
        for VM in $(virsh list --all --name 2>/dev/null | grep -E '_stressVM(_e[0-9]+)?$'); do
            [ -n "${VM}" ] || continue
            echo "===== Deleting VM: ${VM} ====="
            virsh destroy "${VM}" 2>/dev/null || true
            # --nvram first so a UEFI domain's nvram is also removed; fall back to plain undefine.
            virsh undefine "${VM}" --nvram 2>/dev/null \
                || virsh undefine "${VM}" 2>/dev/null || true
            rm -f "${IMG_DIR}/${VM}.qcow2"
            echo "${VM} deleted"
        done

        # Sweep any stray overlay disks whose domain is already gone (never the goldens).
        rm -f "${IMG_DIR}"/*_stressVM.qcow2 "${IMG_DIR}"/*_stressVM_e*.qcow2 2>/dev/null || true
        '''
    }
}

/** Deletes all stress-VM guests (both families share the <OS>_stressVM naming now). */
def deleteVms(String runType) {
    deleteAllStressVmFamilies()
}

/**
 * Write a green "LKP Regression is running" warning box to /etc/motd so anyone who logs in
 * sees the SUT is busy. The original motd is saved once to /etc/motd.lkp_backup (a rerun must
 * not overwrite that backup with our own banner) and restored by clearLkpMotdBanner().
 */
def showLkpMotdBanner() {
    sh '''
    set -eu
    if [ ! -e /etc/motd.lkp_backup ]; then
        if [ -e /etc/motd ]; then cp -p /etc/motd /etc/motd.lkp_backup; else : > /etc/motd.lkp_backup; fi
    fi
    G='\\033[1;32m'; B='\\033[1m'; R='\\033[0m'; W=68
    TOP=$(printf '#%.0s' $(seq $W)); EMP="#$(printf ' %.0s' $(seq $((W-2))))#"
    MSG="LKP Regression is running in this system DON'T USE"; P=$(( (W-2-${#MSG})/2 ))
    { printf "${G}%s${R}\\n" "$TOP"; for i in $(seq 6); do printf "${G}%s${R}\\n" "$EMP"; done
      printf "${G}#${R}%*s${B}%s${R}%*s${G}#${R}\\n" $P "" "$MSG" $((W-2-P-${#MSG})) ""
      for i in $(seq 6); do printf "${G}%s${R}\\n" "$EMP"; done; printf "${G}%s${R}\\n" "$TOP"; } > /etc/motd
    echo "LKP-running banner written to /etc/motd"
    '''

    // Warn everyone already logged in, then give them a minute to save work and log off.
    sh '''
    set +e
    if command -v wall >/dev/null 2>&1; then
        wall "LKP Regression will start in 1 MINUTE"
    else
        echo "wall not found; skipping broadcast"
    fi
    exit 0
    '''
    echo 'Waiting 1 minute before starting LKP regression...'
    sleep(time: 60, unit: 'SECONDS')
}

/** Restore the /etc/motd saved by showLkpMotdBanner(). Safe to call more than once; never fails. */
def clearLkpMotdBanner() {
    sh '''
    set +e
    if [ -e /etc/motd.lkp_backup ]; then
        mv -f /etc/motd.lkp_backup /etc/motd && echo "Restored original /etc/motd"
    else
        echo "No LKP motd backup found; /etc/motd left unchanged"
    fi
    exit 0
    '''
}

/**
 * Kill leftover LKP workloads on the HOST (not in the VMs).
 *
 * When a build is aborted mid-run, `lkp run` and its benchmark processes (hackbench forks
 * heavily, ebizzy, unixbench's harness) plus the lkp monitors (dstat/vmstat/iostat/perf/...
 * launched from /lkp) can survive as orphans and keep loading the box, skewing the *next*
 * run. Manual `kill <pid>` usually fails because (a) the workloads run as root and (b)
 * hackbench spawns thousands of same-named children. This kills by name (not PID), stops the
 * lkp driver first so it cannot respawn, and escalates TERM -> KILL. Best-effort and never
 * fatal: it always exits 0.
 */
def killHostLkpWorkloads() {
    sh '''
    set +e
    echo "==============================================="
    echo "Killing leftover LKP workloads on the HOST"
    echo "==============================================="

    # lkp workloads are normally root-owned. Prefer running as root; if we are not root,
    # try passwordless sudo; otherwise fall back to killing only our own processes.
    if [ "$(id -u)" -eq 0 ]; then
        AS_ROOT=""
    elif sudo -n true 2>/dev/null; then
        AS_ROOT="sudo -n"
    else
        AS_ROOT=""
        echo "WARNING: not root and no passwordless sudo; may only kill own processes."
    fi

    # 1) Stop the lkp driver FIRST so it cannot relaunch jobs/monitors mid-cleanup.
    $AS_ROOT pkill -TERM -f 'lkp/bin/(run-local|run_jobs|lkp)' 2>/dev/null
    $AS_ROOT pkill -TERM -f '[l]kp run'                        2>/dev/null

    # 2) TERM the benchmark processes by exact command name.
    for name in hackbench ebizzy Run multitask looper; do
        $AS_ROOT pkill -TERM -x "$name" 2>/dev/null
    done

    sleep 3

    # 3) Anything still alive gets SIGKILL: the workload binaries by name, plus the whole
    #    /lkp tree (monitors such as dstat/vmstat/iostat/mpstat/sar/perf/meminfo run from there,
    #    as do the unixbench microbenchmarks under /lkp/benchmarks).
    for name in hackbench ebizzy Run multitask looper; do
        $AS_ROOT pkill -KILL -x "$name" 2>/dev/null
    done
    $AS_ROOT pkill -KILL -f '/lkp/' 2>/dev/null

    sleep 1

    # 4) Report survivors so the state is obvious in the log (the grep excludes itself).
    echo "Surviving LKP-related processes (expected: none):"
    ps -eo pid,ppid,user,stat,comm,args 2>/dev/null | grep -Ei 'hackbench|ebizzy|unixbench|/lkp/' | grep -vE 'grep|pkill' || echo "  none"

    exit 0
    '''
}

def resolvePatchAndBaseKernels() {
    switch (params.RPM_KERNEL_BUILD) {
        case 'BUILD_BOTH':
            // Already set in build stages; ensure non-empty
            if (!env.PATCH_KERNEL?.trim()) {
                env.PATCH_KERNEL = getInstalledKernel('with_patch')
            }
            if (!env.BASE_KERNEL?.trim()) {
                env.BASE_KERNEL = getInstalledKernel('base_patch')
            }
            break
        case 'MANUAL_BOTH_NO_BUILD':
            env.PATCH_KERNEL = params.MANUAL_PATCH_KERNEL.trim()
            env.BASE_KERNEL = params.MANUAL_BASE_KERNEL.trim()
            break
        default:
            break
    }
    echo "Resolved PATCH_KERNEL=${env.PATCH_KERNEL} BASE_KERNEL=${env.BASE_KERNEL}"
    initBootFlag()
}

// ---------------------------------------------------------------------------
// Boot target: select the kernel to boot on the agent — a one-time next-boot selection
// (grub2-reboot RHEL-like / grub-reboot Ubuntu/Velinux) when ONE_TIME_BOOT is on, otherwise
// the persistent default (grubby --set-default / grub-set-default) — then reboot from agent
// none (see rebootAndWaitForNode).
// ---------------------------------------------------------------------------

/** LKP_Automated run.sh style: ID= from os-release + grep for distro names. */
def detectBootMethod() {
    return sh(script: '''
set -eu
OS=/etc/os-release

if grep -qiE 'anolis|opencloud|cloudos|openeuler|euler' "$OS"; then
    echo grubby
    exit 0
fi
if grep -qiE 'ubuntu|velinux' "$OS"; then
    echo grub_debian
    exit 0
fi

# Distros the greps above do not name, by ID.
ID=$(grep '^ID=' "$OS" | cut -d= -f2 | tr -d '"' | tr '[:upper:]' '[:lower:]')
case "$ID" in
    centos|rocky|rhel|almalinux|fedora)
        echo grubby
        ;;
    debian)
        echo grub_debian
        ;;
    *)
        if command -v grubby >/dev/null 2>&1; then
            echo grubby
        else
            echo grub_debian
        fi
        ;;
esac
''', returnStdout: true).trim()
}

/**
 * Anolis / OpenCloudOS / openEuler: grubby --set-default is the persistent boot default.
 *
 * No grub2-mkconfig here, on purpose. grubby has already made the selection by the time it
 * returns — it writes saved_entry into grubenv on BLS systems (Anolis 23, OpenCloudOS 9,
 * openEuler) and edits grub.cfg in place on older non-BLS ones — so regenerating the whole
 * menu from /etc/default/grub and /etc/grub.d/* adds nothing and is the one operation here
 * that can leave a machine with no usable boot menu.
 */
def setDefaultKernelGrubby(String kernelRelease) {
    withEnv(["_K=${kernelRelease}", "_ONE_TIME=${ONE_TIME_BOOT ? '1' : '0'}"]) {
        sh '''
        set -eu
        test -e "/boot/vmlinuz-${_K}"

        # One-time next-boot selection (grub2-reboot) when ONE_TIME_BOOT is on: writes
        # next_entry in grubenv only and leaves the persistent default untouched, so a kernel
        # that fails to boot costs one failed boot instead of a console rescue — grub falls
        # back to the persistent default on the boot after.
        #
        # Select by grubby ENTRY ID, not by numeric index. grubby's index does NOT always match
        # the order GRUB resolves next_entry against on BLS systems (grubby may list newest-first
        # while blscfg sorts otherwise), so "grub2-reboot <index>" can boot the wrong kernel —
        # observed in Elves: index 0 (the intended target) came back on the index-1 kernel. The
        # id (<machine-id>-<release>) is the stable, order-independent handle BLS itself uses, so
        # grub2-reboot resolves it to the correct entry. Fall back to the index only if grubby
        # exposes no id.
        ID=$(grubby --info="/boot/vmlinuz-${_K}" 2>/dev/null | sed -n 's/^id=//p' | head -1 | tr -d '"')
        INDEX=$(grubby --info="/boot/vmlinuz-${_K}" 2>/dev/null | sed -n 's/^index=//p' | head -1)
        TARGET="${ID:-${INDEX}}"
        if [ "${_ONE_TIME}" = 1 ] && [ -n "${TARGET}" ] && command -v grub2-reboot >/dev/null 2>&1; then
            grub2-reboot "${TARGET}"
            echo "Next boot only: ${TARGET} (/boot/vmlinuz-${_K})"
            grub2-editenv list | grep '^next_entry=' || true
            exit 0
        fi

        # Persistent default (ONE_TIME_BOOT off, or one-time boot unavailable on this SUT).
        # No grub2-mkconfig here, on purpose (see the function doc above).
        echo "Using grubby (Anolis / OpenCloud / OpenEuler family)"
        grubby --set-default "/boot/vmlinuz-${_K}"
        grubby --default-kernel
        '''
    }
}

/** Ubuntu / Velinux: no grubby — use grub-set-default + update-grub. */
def setDefaultKernelGrubDebian(String kernelRelease) {
    withEnv(["_K=${kernelRelease}", "_ONE_TIME=${ONE_TIME_BOOT ? '1' : '0'}"]) {
        sh '''
        set -eu
        if ! test -e "/boot/vmlinuz-${_K}" && ! test -e "/boot/vmlinuz-${_K}.efi"; then
            echo "ERROR: /boot/vmlinuz-${_K} not found" >&2
            ls -1 /boot/vmlinuz-* >&2 || true
            exit 1
        fi
        GRUB_CFG=/boot/grub/grub.cfg
        [ -f "$GRUB_CFG" ] || GRUB_CFG=/boot/grub2/grub.cfg
        if [ ! -f "$GRUB_CFG" ]; then
            echo "ERROR: no grub.cfg at /boot/grub or /boot/grub2" >&2
            exit 1
        fi
        echo "Using grub-set-default (Ubuntu / Velinux family)"

            if ! grep -q '^GRUB_DEFAULT=saved' /etc/default/grub 2>/dev/null; then
            if grep -q '^GRUB_DEFAULT=' /etc/default/grub 2>/dev/null; then
                    sed -i 's/^GRUB_DEFAULT=.*/GRUB_DEFAULT=saved/' /etc/default/grub
                else
                    echo 'GRUB_DEFAULT=saved' >> /etc/default/grub
                fi
        fi

        # Rebuild the menu first: it picks up a kernel installed without a menu update and
        # puts set default="${saved_entry}" in grub.cfg before saved_entry is written, so a
        # selection is never left in grubenv that grub.cfg does not honour.
                if command -v update-grub >/dev/null 2>&1; then
                    update-grub
                elif command -v grub-mkconfig >/dev/null 2>&1; then
                    grub-mkconfig -o "$GRUB_CFG"
                fi

        SUBMENU=$(grep -E "submenu '.*Advanced options" "$GRUB_CFG" | head -1 | sed -n "s/.*submenu '\\([^']*\\)'.*/\\1/p")
        ENTRY=$(grep -F "with Linux ${_K}" "$GRUB_CFG" | grep menuentry | head -1 | sed -n "s/.*menuentry '\\([^']*\\)'.*/\\1/p")
        if [ -z "$ENTRY" ]; then
            echo "ERROR: no menuentry for kernel ${_K} in $GRUB_CFG" >&2
            exit 1
        fi
        # Every per-kernel entry lives inside the "Advanced options" submenu, so the spec grub
        # needs is "<submenu>><entry>" when that submenu exists.
        if [ -n "$SUBMENU" ]; then
            TARGET_ENTRY="${SUBMENU}>${ENTRY}"
        else
            TARGET_ENTRY="${ENTRY}"
        fi

        # One-time next-boot selection (grub-reboot) when ONE_TIME_BOOT is on and next_entry is
        # supported: writes next_entry only, leaving the persistent default (set just above via
        # GRUB_DEFAULT=saved) untouched, so a kernel that panics costs one failed boot.
        if [ "${_ONE_TIME}" = 1 ] && command -v grub-reboot >/dev/null 2>&1 && grep -q 'next_entry' "$GRUB_CFG"; then
        grub-reboot "$TARGET_ENTRY"
        echo "Next boot only: $TARGET_ENTRY"
        grub-editenv list | grep '^next_entry=' || true
            exit 0
        fi

        # Persistent default (ONE_TIME_BOOT off, or one-time boot unavailable on this SUT).
        grub-set-default "$TARGET_ENTRY"
        echo "grub-set-default $TARGET_ENTRY"
        grub-editenv list | grep '^saved_entry=' || true
        '''
    }
}

/**
 * Give the kernel about to be booted an initramfs and a boot entry, creating what is missing.
 *
 * The post-install hooks of `make binrpm-pkg` / `make bindeb-pkg` (kernel-install,
 * new-kernel-pkg) frequently do nothing on these distros: the image lands in /boot with no
 * initramfs and no entry, and the kernel drops into the dracut emergency shell — the agent
 * never reconnects and the SUT needs console recovery.
 *
 * Entries are added with grubby, not a menu regeneration: grubby writes to the config the
 * machine really boots from (the EFI one on UEFI hosts, where `grub2-mkconfig -o
 * /boot/grub2/grub.cfg` has no effect at all) and --copy-default carries over root=,
 * rd.lvm.* and console= from an entry that works.
 *
 * Runs before every boot selection, so it also covers kernels this run did not install
 * (MANUAL_* modes, ALREADY_BOOTED).
 */
def prepareBootArtifacts(String kernelRelease) {
    withEnv(["_K=${kernelRelease}"]) {
        sh '''
        set -eu

        IMG="/boot/vmlinuz-${_K}"
        [ -e "${IMG}" ] || IMG="/boot/vmlinuz-${_K}.efi"
        if [ ! -e "${IMG}" ]; then
            echo "ERROR: no /boot/vmlinuz-${_K} — nothing to boot." >&2
            ls -1 /boot/vmlinuz-* >&2 || true
            exit 1
        fi

        # initramfs-<ver>.img on RHEL-like, initrd.img-<ver> on Debian-like.
        initrd_path() {
            for f in "/boot/initramfs-${_K}.img" "/boot/initrd.img-${_K}"; do
                if [ -s "$f" ]; then
                    printf '%s\\n' "$f"
                    return 0
                fi
            done
            return 1
        }

        INITRD=$(initrd_path || true)

        # A stale image is as unbootable as a missing one: reinstalling the same kernel
        # release replaces /lib/modules/<rel> without refreshing the initramfs beside it.
        if [ -n "${INITRD}" ] && [ "${IMG}" -nt "${INITRD}" ]; then
            echo "${INITRD} is older than ${IMG}; treating it as stale."
            INITRD=""
        fi

        if [ -z "${INITRD}" ]; then
            echo "No usable initramfs for ${_K} (missing or stale). Building it now."
            if command -v dracut >/dev/null 2>&1; then
                dracut -f "/boot/initramfs-${_K}.img" "${_K}"
            elif command -v update-initramfs >/dev/null 2>&1; then
                update-initramfs -c -k "${_K}"
            elif command -v mkinitramfs >/dev/null 2>&1; then
                mkinitramfs -o "/boot/initrd.img-${_K}" "${_K}"
            else
                echo "ERROR: neither dracut nor update-initramfs on this SUT; cannot build an initramfs." >&2
                exit 1
            fi
            INITRD=$(initrd_path || true)
        fi

        if [ -z "${INITRD}" ]; then
            echo "ERROR: still no initramfs for ${_K} after rebuilding; refusing to boot it." >&2
            ls -1 /boot/initramfs-*.img /boot/initrd.img-* 2>/dev/null >&2 || echo "  (none)" >&2
            ls -1d "/lib/modules/${_K}" >&2 || echo "  /lib/modules/${_K} is missing" >&2
            df -h /boot >&2 || true
            exit 1
        fi

        # An image that exists but is tiny means the build was cut short (usually ENOSPC on
        # /boot). Real hostonly images are tens of MB; 2 MB is well below any of them.
        SIZE_KB=$(du -k "${INITRD}" | cut -f1)
        if [ "${SIZE_KB}" -lt 2048 ]; then
            echo "ERROR: ${INITRD} is only ${SIZE_KB} KB — truncated, not a usable initramfs." >&2
            df -h /boot >&2 || true
            exit 1
        fi

        echo "Boot artifacts OK: ${IMG} + ${INITRD} (${SIZE_KB} KB)"

        # Debian-like systems have no grubby: their entries come from a menu regeneration,
        # which has to happen here so the checks that follow have something to inspect. The
        # initramfs built above is picked up by the same run.
        if ! command -v grubby >/dev/null 2>&1; then
            GRUB_CFG=/boot/grub/grub.cfg
            [ -f "${GRUB_CFG}" ] || GRUB_CFG=/boot/grub2/grub.cfg
            if [ -f "${GRUB_CFG}" ] && ! grep -qF "with Linux ${_K}" "${GRUB_CFG}"; then
                echo "No menu entry for ${_K} in ${GRUB_CFG}; regenerating the menu."
                if command -v update-grub >/dev/null 2>&1; then
                    update-grub
                elif command -v grub-mkconfig >/dev/null 2>&1; then
                    grub-mkconfig -o "${GRUB_CFG}"
                fi
            fi
            exit 0
        fi

        grubby_index() {
            grubby --info="${IMG}" 2>/dev/null | sed -n 's/^index=//p' | head -1
        }

        if [ -z "$(grubby_index)" ]; then
            echo "grubby has no boot entry for ${_K}; adding one from the current default."
            grubby --add-kernel="${IMG}" --initrd="${INITRD}" --title="${_K}" --copy-default
        fi

        if [ -z "$(grubby_index)" ]; then
            echo "ERROR: grubby still has no boot entry for ${IMG}." >&2
            grubby --info=ALL >&2 || true
            exit 1
        fi

        grubby --info="${IMG}"
        '''
    }
}

/**
 * Red-flag sweep over everything the boot depends on, between prepareBootArtifacts and the
 * reboot. Collects every problem before reporting, so one look at the log tells you all of
 * what to fix on the SUT.
 *
 * The checks are the failures that have actually cost console recovery time: a half-installed
 * kernel image or module tree, an entry whose root= or LVM/RAID/LUKS arguments do not match
 * the running system (a --copy-default that copied the wrong entry), an initrd= pointing at
 * nothing, and whether the running kernel is still bootable as a fallback — which is what
 * decides between a five-minute and a two-hour recovery.
 */
def preflightBootChecks(String kernelRelease) {
    withEnv(["_K=${kernelRelease}", "_M=${env.BOOT_METHOD ?: 'grubby'}"]) {
        sh '''
        set -eu

        FAIL=0
        red() {
            echo "RED FLAG: $*" >&2
            FAIL=1
        }

        IMG="/boot/vmlinuz-${_K}"
        [ -e "${IMG}" ] || IMG="/boot/vmlinuz-${_K}.efi"

        echo "===== Pre-boot checks for ${_K} ====="
        echo "Currently running: $(uname -r)"

        if [ ! -e "${IMG}" ]; then
            echo "RED FLAG: no kernel image for ${_K} in /boot; aborting before the reboot." >&2
            exit 1
        fi

        # root= can be written as a device, a UUID or a LABEL, so compare what they resolve
        # to rather than the strings — otherwise a purely cosmetic difference aborts the run.
        resolve_root() {
            case "$1" in
                UUID=*)     blkid -U "${1#UUID=}" 2>/dev/null || true ;;
                LABEL=*)    blkid -L "${1#LABEL=}" 2>/dev/null || true ;;
                PARTUUID=*) blkid -t "PARTUUID=${1#PARTUUID=}" -o device 2>/dev/null | head -1 || true ;;
                /dev/*)     readlink -f "$1" 2>/dev/null || printf '%s' "$1" ;;
                *)          printf '%s' "$1" ;;
            esac
        }

        # --- kernel image ------------------------------------------------------------
        IMG_KB=$(du -k "${IMG}" | cut -f1)
        echo "Kernel image     : ${IMG} (${IMG_KB} KB)"
        if [ "${IMG_KB}" -lt 1024 ]; then
            red "${IMG} is only ${IMG_KB} KB — that is not a kernel image."
        fi
        if command -v file >/dev/null 2>&1; then
            if ! file -b "${IMG}" | grep -qiE 'kernel|bzimage'; then
                red "${IMG} is not recognised as a Linux kernel image: $(file -b "${IMG}")"
            fi
        fi

        # --- module tree -------------------------------------------------------------
        if [ ! -d "/lib/modules/${_K}" ]; then
            red "/lib/modules/${_K} is missing — the kernel package did not install its modules."
        elif [ ! -s "/lib/modules/${_K}/modules.dep" ]; then
            red "/lib/modules/${_K}/modules.dep is missing or empty — depmod never ran (fix: depmod -a ${_K})."
        else
            echo "Module tree      : /lib/modules/${_K} OK"
        fi

        # --- /boot space -------------------------------------------------------------
        BOOT_FREE_MB=$(df -Pm /boot | awk 'NR==2 {print $4}')
        echo "/boot free       : ${BOOT_FREE_MB} MB"
        if [ -n "${BOOT_FREE_MB}" ] && [ "${BOOT_FREE_MB}" -lt 20 ]; then
            red "/boot has only ${BOOT_FREE_MB} MB free; the next kernel or initramfs write will be truncated."
        fi

        # Arguments the running system needs to find its root filesystem. If the entry for
        # the new kernel is missing any of them it will not reach userspace.
        RUN_ROOT=$(tr ' ' '\\n' < /proc/cmdline | sed -n 's/^root=//p' | head -1)
        CRITICAL=$(tr ' ' '\\n' < /proc/cmdline | grep -E '^(rd\\.lvm\\.lv|rd\\.md\\.uuid|rd\\.luks\\.uuid|rootflags)=' || true)

        if [ "${_M}" = grub_debian ]; then
            GRUB_CFG=/boot/grub/grub.cfg
            [ -f "${GRUB_CFG}" ] || GRUB_CFG=/boot/grub2/grub.cfg

            if ! grep -qF "with Linux ${_K}" "${GRUB_CFG}"; then
                red "no menuentry for ${_K} in ${GRUB_CFG} (fix: update-grub)."
            fi
            if ! grep -qF "initrd.img-${_K}" "${GRUB_CFG}"; then
                red "no initrd line for ${_K} in ${GRUB_CFG} — the entry would boot without an initramfs."
            fi
            # Not a red flag: setDefaultKernelGrubDebian sets this immediately after, and
            # verifyBootSelection fails the stage if the selection still did not stick.
            if ! grep -q '^GRUB_DEFAULT=saved' /etc/default/grub 2>/dev/null; then
                echo "NOTE: GRUB_DEFAULT is not yet 'saved' in /etc/default/grub; the boot selection step sets it."
            fi

            NEW_ARGS=$(grep -F "vmlinuz-${_K}" "${GRUB_CFG}" | grep -E '^[[:space:]]*linux' | head -1 || true)
            NEW_ROOT=$(printf '%s' "${NEW_ARGS}" | tr ' ' '\\n' | sed -n 's/^root=//p' | head -1)

            RUN_ENTRY_OK=1
            grep -qF "with Linux $(uname -r)" "${GRUB_CFG}" || RUN_ENTRY_OK=0
        else
            ENTRY=$(grubby --info="${IMG}" 2>/dev/null || true)
            if [ -z "${ENTRY}" ]; then
                red "grubby has no boot entry for ${IMG}."
            fi

            ENTRY_INITRD=$(printf '%s\\n' "${ENTRY}" | sed -n 's/^initrd=//p' | head -1 | tr -d '"' | awk '{print $1}')
            if [ -z "${ENTRY_INITRD}" ]; then
                red "the boot entry for ${_K} has no initrd= line."
            elif [ ! -s "${ENTRY_INITRD}" ]; then
                red "the boot entry points at ${ENTRY_INITRD}, which is not on disk."
            else
                echo "Entry initrd     : ${ENTRY_INITRD} OK"
            fi

            NEW_ROOT=$(printf '%s\\n' "${ENTRY}" | sed -n 's/^root=//p' | head -1 | tr -d '"')
            NEW_ARGS=$(printf '%s\\n' "${ENTRY}" | sed -n 's/^args=//p' | head -1 | tr -d '"')

            # Fallback: the kernel running right now must stay selectable from the menu.
            RUN_ENTRY_OK=1
            RUN_ENTRY=$(grubby --info="/boot/vmlinuz-$(uname -r)" 2>/dev/null || true)
            [ -n "${RUN_ENTRY}" ] || RUN_ENTRY_OK=0
        fi

        # Some entries carry root= inside the argument list instead of as its own field.
        if [ -z "${NEW_ROOT}" ]; then
            NEW_ROOT=$(printf '%s' "${NEW_ARGS}" | tr ' ' '\\n' | sed -n 's/^root=//p' | head -1)
        fi

        echo "root= running    : ${RUN_ROOT:-<none>}"
        echo "root= new entry  : ${NEW_ROOT:-<none>}"
        if [ -z "${NEW_ROOT}" ]; then
            red "the boot entry for ${_K} has no root= — it cannot mount a root filesystem."
        elif [ -n "${RUN_ROOT}" ] && [ "${RUN_ROOT}" != "${NEW_ROOT}" ]; then
            R_RUN=$(resolve_root "${RUN_ROOT}")
            R_NEW=$(resolve_root "${NEW_ROOT}")
            if [ -n "${R_RUN}" ] && [ "${R_RUN}" = "${R_NEW}" ]; then
                echo "root= written differently but resolves to the same device (${R_RUN})."
            elif [ -z "${R_RUN}" ] || [ -z "${R_NEW}" ]; then
                echo "WARNING: root=${NEW_ROOT} differs from the running root=${RUN_ROOT} and neither could be resolved to a device." >&2
            else
                red "the new entry boots root=${NEW_ROOT} (${R_NEW}) but this system runs root=${RUN_ROOT} (${R_RUN})."
            fi
        fi

        for arg in ${CRITICAL}; do
            case " ${NEW_ARGS} ${NEW_ROOT} " in
                *" ${arg} "*) ;;
                *) red "the running kernel needs ${arg} but the entry for ${_K} does not have it." ;;
            esac
        done

        if [ "${RUN_ENTRY_OK}" != 1 ]; then
            red "the running kernel $(uname -r) has no boot entry — if ${_K} fails there is nothing to fall back to at the console."
        else
            echo "Fallback entry   : $(uname -r) OK"
        fi

        if [ "${FAIL}" != 0 ]; then
            echo "Aborting before the reboot: the checks above must be fixed first." >&2
            exit 1
        fi
        echo "===== All pre-boot checks passed for ${_K} ====="
        '''
    }
}

/**
 * Confirm the boot selection was actually recorded, after the family-specific step ran.
 * A grub-set-default that GRUB ignores, or a grubby default that silently stayed on the old
 * kernel, otherwise only shows up as a puzzling "booted the wrong kernel" much later.
 */
def verifyBootSelection(String kernelRelease) {
    withEnv(["_K=${kernelRelease}", "_M=${env.BOOT_METHOD ?: 'grubby'}", "_ONE_TIME=${ONE_TIME_BOOT ? '1' : '0'}"]) {
        sh '''
        set -eu
        # One-time boot arms next_entry and deliberately leaves the persistent default alone,
        # so verify next_entry rather than the default here. If it is not set the code fell back
        # to the persistent default, so drop through to the default check below. The booted
        # kernel is confirmed authoritatively after the reboot by verifyRunningKernel.
        if [ "${_ONE_TIME}" = 1 ]; then
            if [ "${_M}" = grub_debian ]; then
                NEXT=$(grub-editenv list 2>/dev/null | sed -n 's/^next_entry=//p' | head -1)
            else
                NEXT=$(grub2-editenv list 2>/dev/null | sed -n 's/^next_entry=//p' | head -1)
            fi
            if [ -n "${NEXT}" ]; then
                echo "OK: one-time boot armed for the next reboot (next_entry=${NEXT})."
                exit 0
            fi
            echo "NOTE: one-time boot fell back to the persistent default; verifying that instead."
        fi
        if [ "${_M}" = grub_debian ]; then
            SAVED=$(grub-editenv list 2>/dev/null | sed -n 's/^saved_entry=//p' | head -1)
            echo "saved_entry = ${SAVED:-<unset>}"
            case "${SAVED}" in
                "") echo "ERROR: no saved_entry recorded; the SUT would boot its old default." >&2; exit 1 ;;
                *"${_K}"*) echo "OK: the saved default selects ${_K}." ;;
                *) echo "ERROR: saved_entry '${SAVED}' does not name ${_K}." >&2; exit 1 ;;
            esac
        else
            DEF=$(grubby --default-kernel 2>/dev/null || true)
            echo "grubby --default-kernel = ${DEF:-<none>}"
            case "${DEF}" in
                *"vmlinuz-${_K}"*) echo "OK: the default kernel is ${_K}." ;;
                *) echo "ERROR: default kernel is '${DEF}', expected /boot/vmlinuz-${_K}." >&2; exit 1 ;;
            esac
        fi
        '''
    }
}

/**
 * Keep the GRUB menu on screen for GRUB_MENU_TIMEOUT seconds, so a kernel that fails to
 * boot can be stepped over from the console.
 *
 * Deliberately regenerates no menu: /etc/default/grub is updated so the value survives a
 * future regeneration, the live grub.cfg is patched one line at a time, and the RHEL
 * menu-auto-hide flag is cleared in grubenv only. Ubuntu/Velinux keep their live grub.cfg
 * (its timeouts sit indented inside if-blocks, which the grep below skips); the update-grub
 * in setDefaultKernelGrubDebian applies /etc/default/grub there.
 */
def applyGrubMenuTimeout() {
    if (GRUB_MENU_TIMEOUT <= 0) {
        echo 'GRUB_MENU_TIMEOUT=0: leaving the SUT boot menu timeout as it is.'
        return
    }
    withEnv(["_T=${GRUB_MENU_TIMEOUT}"]) {
        sh '''
        set -eu

        set_key() {
            if grep -q "^$1=" /etc/default/grub 2>/dev/null; then
                sed -i "s|^$1=.*|$1=$2|" /etc/default/grub
            else
                echo "$1=$2" >> /etc/default/grub
            fi
        }

        if [ -f /etc/default/grub ]; then
            set_key GRUB_TIMEOUT "${_T}"
            set_key GRUB_TIMEOUT_STYLE menu
            # Ubuntu waits at the menu FOREVER after a failed boot (recordfail) unless this
            # is finite, which would strand a headless SUT until someone walks up to it.
            set_key GRUB_RECORDFAIL_TIMEOUT "${_T}"
            echo "/etc/default/grub: timeout ${_T}s, style menu"
        fi

        for CFG in /boot/grub2/grub.cfg /boot/grub/grub.cfg; do
            [ -f "$CFG" ] || continue
            grep -qE '^[[:space:]]*set timeout=' "$CFG" || continue
            [ -f "${CFG}.jenkins.bak" ] || cp -a "$CFG" "${CFG}.jenkins.bak"
            sed -i -E "s/^([[:space:]]*)set timeout=.*/\\1set timeout=${_T}/" "$CFG"
            sed -i -E "s/^([[:space:]]*)set timeout_style=hidden/\\1set timeout_style=menu/" "$CFG"
            echo "$CFG: timeout ${_T}s (backup at ${CFG}.jenkins.bak)"
        done

        # RHEL 8+ hides the menu completely on a box that keeps booting successfully.
        # grubenv only — no menu is touched.
        if command -v grub2-editenv >/dev/null 2>&1; then
            grub2-editenv - set menu_auto_hide=0 || true
        fi
        '''
    }
}

/**
 * Selects the boot-target kernel per env.BOOT_METHOD: a one-time next-boot selection when
 * ONE_TIME_BOOT is on (persistent default left untouched), otherwise the persistent default
 * for the next and every later boot.
 *
 * Nothing here reboots — that is the following agent-none stage — but this is the last point
 * where the SUT is known to be reachable, so the whole boot is validated here: build any
 * missing initramfs / boot entry, sweep for red flags, make sure the menu will be waiting at
 * the console, select the kernel, then confirm the selection was recorded.
 */
def setDefaultKernel(String kernelRelease) {
    prepareBootArtifacts(kernelRelease)
    preflightBootChecks(kernelRelease)
    applyGrubMenuTimeout()
    if (env.BOOT_METHOD == 'grub_debian') {
        setDefaultKernelGrubDebian(kernelRelease)
    } else {
        setDefaultKernelGrubby(kernelRelease)
    }
    verifyBootSelection(kernelRelease)
}

def requireBothKernelsForBothMode() {
    if (!env.BASE_KERNEL?.trim() || !env.PATCH_KERNEL?.trim()) {
        error('BOTH_BASE_PATCH_LKP requires non-empty BASE_KERNEL and PATCH_KERNEL (resolve/build/manual).')
    }
}

def requireKernel(String kernelRelease, String label) {
    if (!kernelRelease?.trim()) {
        error("${params.LKP_RUN_OPTIONS} requires a non-empty ${label} (resolve/build/manual).")
    }
}

/**
 * Shared step bodies, extracted from the declarative stages so the generated
 * pipeline method stays well under the JVM 64 KB method-size limit (see the
 * "Method too large" CpsCompilationException). Each is called from a stage's
 * `steps { script { ... } }`, replacing several inlined lines with one call.
 */
def verifyRunningKernel(String expectedRelease, String label = null) {
    if (!expectedRelease?.trim()) {
        error("verifyRunningKernel: expected kernel release is empty${label ? " (${label})" : ''}; cannot verify boot.")
    }
    // A box that came back on a different kernel must not run LKP: failing the stage here
    // takes the pipeline straight to post instead of collecting results for the wrong kernel.
    withEnv(["_EXPECTED=${expectedRelease}", "_LABEL=${label?.trim() ? label.trim() + ' ' : ''}"]) {
        sh '''
    set -eu
        # withEnv drops _LABEL when the label is blank, leaving it UNSET; normalise so the
        # bare ${_LABEL} references below do not trip 'set -u'.
        _LABEL="${_LABEL:-}"
        RUNNING=$(uname -r)
        echo "Running kernel         : ${RUNNING}"
        echo "Expected ${_LABEL}kernel : ${_EXPECTED}"
        if [ "${RUNNING}" != "${_EXPECTED}" ]; then
            echo "ERROR: the SUT did not boot the expected ${_LABEL}kernel ${_EXPECTED}; it is running ${RUNNING}." >&2
        exit 1
    fi
        echo "OK: booted into the expected ${_LABEL}kernel."
        '''
    }
}

/** Host LKP on the current kernel, then best-effort cleanup of any stress VM families. */
def hostLkpThenCleanup() {
    try {
        Lkp_test('host')
    } finally {
        deleteAllStressVmFamilies()
    }
}

/** Create VMs of the given run type, run LKP with the matching report context, always delete. */
def vmLkpRun(String runType, String reportContext) {
    createVms(runType)
    try {
        Lkp_test(reportContext)
    } finally {
        deleteVms(runType)
    }
}

/** Selects the boot-target kernel (one-time boot when ONE_TIME_BOOT is on, else persistent default; see setDefaultKernel); sets env.REBOOT_MESSAGE for the following agent-none reboot stage. */
def setGrubbyDefaultKernel(String kernelRelease, String rebootMessage) {
    if (!kernelRelease?.trim()) {
        error('setGrubbyDefaultKernel: empty kernel release')
    }
    env.REBOOT_MESSAGE = rebootMessage
    setDefaultKernel(kernelRelease)
}

/** Sets the default kernel to PATCH/BASE per LKP_RUN_OPTIONS, plus the reboot message. */
def prepareBootTargetGrubby() {
    String role = bootTargetKernelRole()
    if (!role) {
            return
    }
    String target = (role == 'PATCH' ? env.PATCH_KERNEL : env.BASE_KERNEL)?.trim()
    if (!target) {
        error("LKP_RUN_OPTIONS=${params.LKP_RUN_OPTIONS} requires a non-empty ${role}_KERNEL.")
    }

    // Remembered so the post-reboot verify stage can confirm the box came back on it.
    env.BOOT_TARGET_KERNEL = target
    env.REBOOT_MESSAGE = "Rebooting into ${target} ..."

    setDefaultKernel(target)
}

// ---------------------------------------------------------------------------
// Reboot / build / install
// ---------------------------------------------------------------------------

/**
 * Reboot the SUT then wait for the Jenkins agent to reconnect.
 * Call from a stage with agent none. Do NOT wrap the wait in bare node {} — that can
 * schedule on the offline SUT and fail with AgentOfflineException after ~1 min.
 */
def rebootAndWaitForNode(String message) {
    try {
        node(params.NODE_LABEL) {
            sh """
            set -eu
            echo "${message}"
            reboot
            """
        }
    } catch (Throwable t) {
        echo "Agent disconnected for reboot (expected): ${t.message?.take(120)}"
    }
    echo "Waiting up to 15 min for agent ${params.NODE_LABEL} after reboot..."
    sleep 60
    timeout(time: 15, unit: 'MINUTES') {
        waitUntil {
            def ok = false
            try {
                node(params.NODE_LABEL) {
                    sh 'set -eu; echo agent is back online'
                    ok = true
                }
            } catch (Throwable t) {
                echo "Still waiting: ${t.message?.take(120)}"
            }
            return ok
        }
    }
}

/** RHEL-like (grubby) builds binrpm-pkg; Ubuntu/Velinux (grub_debian) builds bindeb-pkg — binrpm-pkg does not work there. */
def buildKernel(prefix) {
    // EXTRA_CONFIGS_ENABLE / EXTRA_CONFIGS_DISABLE apply to the PATCH kernel only.
    // The BASE kernel is built purely on the selected defconfig (CHOOSE_BUILD_CONFIG)
    // so it stays a clean reference point; only the patched kernel carries the config
    // deltas under test.
    boolean applyExtraConfigs = (prefix == 'with_patch')
    if (!applyExtraConfigs) {
        echo "BASE build (${prefix}): building on ${params.CHOOSE_BUILD_CONFIG ?: 'Velinux'} config only; EXTRA_CONFIGS_ENABLE/DISABLE are not applied (PATCH-kernel only)."
    }
    dir(env.DIR) {
        withEnv([
            "_PREFIX=${prefix}",
            "_DIR=${env.DIR}",
            "_DEB=${env.BOOT_METHOD == 'grub_debian' ? '1' : '0'}",
            "_CFG_TARGET=${params.CHOOSE_BUILD_CONFIG ?: 'Velinux'}",
            "_CUSTOM_CFG=${params.CUSTOM_CONFIG ?: ''}",
            "_CFG_ENABLE=${applyExtraConfigs ? (params.EXTRA_CONFIGS_ENABLE ?: '') : ''}",
            "_CFG_DISABLE=${applyExtraConfigs ? (params.EXTRA_CONFIGS_DISABLE ?: '') : ''}"
        ]) {
            sh '''
            set -eu

            if [ "${_DEB}" = 1 ]; then
                # bindeb-pkg drops .debs ONE DIR ABOVE the tree; wipe stale ones first.
                rm -f "${_DIR}"/../linux-image-*.deb "${_DIR}"/../linux-headers-*.deb "${_DIR}"/../linux-libc-dev_*.deb
            else
                # Wipe stale binrpm-pkg output: the whole build-tree rpmbuild, plus only
                # THIS LOCALVERSION under ~/rpmbuild (never the whole tree).
                rm -rf "${_DIR}/rpmbuild"
                rm -f "${HOME}/rpmbuild/RPMS"/*/kernel-[0-9]*"${_PREFIX}"*.rpm \
                      "${HOME}/rpmbuild/SRPMS"/kernel-[0-9]*"${_PREFIX}"*.rpm 2>/dev/null || true
            fi

            if [ ! -x scripts/config ]; then
                echo "ERROR: scripts/config is missing from this kernel tree." >&2
                exit 1
            fi

            # Clean tree, then apply the selected distro config (CHOOSE_BUILD_CONFIG).
            make mrproper
            echo "Configuring kernel: ${_CFG_TARGET}"
            case "${_CFG_TARGET}" in
                Velinux)
                    [ -f config.x86_64 ] || { echo "ERROR: config.x86_64 not found in $(pwd)" >&2; exit 1; }
                    cp config.x86_64 .config
                    make olddefconfig
                    ;;
                Anolis)    make anolis_defconfig ;;
                OpenEuler) make openeuler_defconfig ;;
                OpenCloud) make tencentconfig ;;
                others)
                    [ -n "${_CUSTOM_CFG:-}" ] || { echo "ERROR: others requires CUSTOM_CONFIG." >&2; exit 1; }
                    make "${_CUSTOM_CFG}"
                    ;;
                *) echo "ERROR: unknown CHOOSE_BUILD_CONFIG='${_CFG_TARGET}'." >&2; exit 1 ;;
            esac

            # Debian/veLinux/Ubuntu: these symbols break the build regardless of the config target
            # (cert keyrings point at files absent from this tree, DEBUG_INFO_BTF needs a matching
            # pahole, NET_VENDOR_NETRONOME fails to compile). Drop them, then re-resolve.
            if [ "${_DEB}" = 1 ]; then
                scripts/config --disable SYSTEM_TRUSTED_KEYS
                scripts/config --disable SYSTEM_REVOCATION_KEYS
                scripts/config --disable CONFIG_DEBUG_INFO_BTF
                scripts/config --disable NET_VENDOR_NETRONOME
                make olddefconfig
            fi

            # ---- LOCALVERSION ------------------------------------------------------------
            # This is what makes the built package identifiable later: the install step
            # matches on it and the boot stages use it as the kernel release.
            # Debian package names cannot contain '_': `make bindeb-pkg` names the package
            # linux-image-<KERNELRELEASE>, and dh_listpackages rejects an underscore
            # ("with_patch"). So on grub_debian use a hyphenated LOCALVERSION (with-patch);
            # RHEL/rpm keeps the underscore form (rpm is fine with it).
            if [ "${_DEB}" = 1 ]; then
                LV="-$(printf '%s' "${_PREFIX}" | tr '_' '-')"
            else
                LV="-${_PREFIX}"
            fi
            scripts/config --file .config --set-str CONFIG_LOCALVERSION "${LV}"

            # ---- EXTRA_CONFIGS_ENABLE / EXTRA_CONFIGS_DISABLE ----------------------------
            # Both boxes take .config lines. Written to files rather than piped so that a
            # bad entry can abort the build from inside the loop.
            # Use ':-' defaults: Jenkins withEnv drops a variable set to an empty string, so
            # these are UNSET (not empty) whenever the box is blank or, for the BASE build,
            # intentionally not passed. Under 'set -u' a bare ${_CFG_ENABLE} would then abort.
            printf '%s\\n' "${_CFG_ENABLE:-}"  > .jenkins_cfg_enable
            printf '%s\\n' "${_CFG_DISABLE:-}" > .jenkins_cfg_disable

            # Normalise one entry into CFG_NAME / CFG_VALUE. Accepts the .config spellings:
            # CONFIG_X, CONFIG_X=y|m|n|42|"str" and "# CONFIG_X is not set" (-> n).
            # Returns 1 for a blank line, 2 for anything that is not a CONFIG_ symbol.
            parse_cfg_line() {
                LINE=$(printf '%s' "$1" | tr -d '\\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
                case "${LINE}" in
                    '#'*)
                        LINE=$(printf '%s' "${LINE}" | sed -n 's/^#[[:space:]]*\\(CONFIG_[A-Za-z0-9_]*\\).*/\\1/p')
                        [ -n "${LINE}" ] || return 1
                        LINE="${LINE}=n"
                        ;;
                    '') return 1 ;;
                esac
                case "${LINE}" in
                    *=*) ;;
                    *)   LINE="${LINE}=y" ;;
                esac
                CFG_NAME=${LINE%%=*}
                CFG_VALUE=${LINE#*=}
                case "${CFG_NAME}" in
                    CONFIG_*) return 0 ;;
                    *)        return 2 ;;
                esac
            }

            apply_config_list() {
                LIST_MODE="$1"
                LIST_FILE="$2"
                while IFS= read -r RAW; do
                    RC=0
                    parse_cfg_line "${RAW}" || RC=$?
                    if [ "${RC}" = 1 ]; then
                        continue
                    fi
                    if [ "${RC}" != 0 ]; then
                        echo "ERROR: '${RAW}' is not a CONFIG_* entry (${LIST_MODE} list)." >&2
                        exit 1
                    fi

                    if [ "${LIST_MODE}" = disable ] || [ "${CFG_VALUE}" = n ]; then
                        scripts/config --file .config --disable "${CFG_NAME}"
                        echo "  disable ${CFG_NAME}"
                        continue
                    fi

                    case "${CFG_VALUE}" in
                        y)    scripts/config --file .config --enable "${CFG_NAME}" ;;
                        m)    scripts/config --file .config --module "${CFG_NAME}" ;;
                        \\"*) scripts/config --file .config --set-str "${CFG_NAME}" "$(printf '%s' "${CFG_VALUE}" | sed 's/^"//;s/"$//')" ;;
                        *)    scripts/config --file .config --set-val "${CFG_NAME}" "${CFG_VALUE}" ;;
                    esac
                    echo "  ${CFG_NAME}=${CFG_VALUE}"
                done < "${LIST_FILE}"
            }

            if [ -s .jenkins_cfg_enable ] && grep -q '[^[:space:]]' .jenkins_cfg_enable; then
                echo "Applying EXTRA_CONFIGS_ENABLE:"
                apply_config_list enable .jenkins_cfg_enable
            fi
            if [ -s .jenkins_cfg_disable ] && grep -q '[^[:space:]]' .jenkins_cfg_disable; then
                echo "Applying EXTRA_CONFIGS_DISABLE:"
                apply_config_list disable .jenkins_cfg_disable
            fi

            # Resolve the dependencies of everything set above.
            make olddefconfig

            # olddefconfig silently drops a symbol whose dependencies are unmet, so compare
            # what was asked for against what the .config ends up with rather than letting
            # the run test a configuration nobody selected.
            for f in .jenkins_cfg_enable .jenkins_cfg_disable; do
                while IFS= read -r RAW; do
                    RC=0
                    parse_cfg_line "${RAW}" || RC=$?
                    if [ "${RC}" != 0 ]; then
                        continue
                    fi
                    WANT="${CFG_VALUE}"
                    if [ "${f}" = .jenkins_cfg_disable ]; then
                        WANT=n
                    fi
                    GOT=$(sed -n "s/^${CFG_NAME}=//p" .config | head -1)
                    [ -n "${GOT}" ] || GOT=n
                    if [ "${GOT}" != "${WANT}" ]; then
                        echo "WARNING: asked for ${CFG_NAME}=${WANT} but .config has ${CFG_NAME}=${GOT} after olddefconfig." >&2
                    fi
                done < "${f}"
            done

            rm -f .jenkins_cfg_enable .jenkins_cfg_disable
            grep '^CONFIG_LOCALVERSION=' .config

            # A kernel that cannot reach its root filesystem is a guaranteed console recovery, so
            # WARN (all distros) if a boot-critical symbol is missing. veLinux's config.x86_64 and
            # the RHEL-family distro defconfigs normally carry these; the checks catch a config that
            # would not reach root. Warnings only -- a false alarm must not stop a build.
            ROOT_FSTYPE=$(findmnt -no FSTYPE / 2>/dev/null || true)
            ROOT_SOURCE=$(findmnt -no SOURCE / 2>/dev/null || true)
            warn_cfg() {
                if ! grep -qE "^$1=(y|m)" .config; then
                    echo "WARNING: $1 is not enabled — $2" >&2
                fi
            }
            warn_cfg CONFIG_BLK_DEV_INITRD "this SUT boots through an initramfs."
            warn_cfg CONFIG_DEVTMPFS "udev cannot come up without devtmpfs."
            case "${ROOT_FSTYPE}" in
                ext4)  warn_cfg CONFIG_EXT4_FS  "/ is ext4." ;;
                xfs)   warn_cfg CONFIG_XFS_FS   "/ is xfs." ;;
                btrfs) warn_cfg CONFIG_BTRFS_FS "/ is btrfs." ;;
            esac
            case "${ROOT_SOURCE}" in
                *nvme*)          warn_cfg CONFIG_BLK_DEV_NVME "/ is on an NVMe device." ;;
                *mapper*|*/dm-*) warn_cfg CONFIG_BLK_DEV_DM   "/ is on device-mapper (LVM)." ;;
            esac

            if [ "${_DEB}" = 1 ]; then
                # bindeb-pkg compiles in-tree (big FS) and drops the .debs one dir above (also
                # big FS); only the compiler's temp files default to /tmp, which is often on a
                # small root FS. Point TMPDIR at the build tree so those land on the big FS too.
                mkdir -p "${_DIR}/.build-tmp"
                TMPDIR="${_DIR}/.build-tmp" make -j"$(nproc)" bindeb-pkg
            else
                # Build the RPM under the kernel source tree (${_DIR}/rpmbuild), which sits on a
                # large filesystem, instead of ~/rpmbuild. When Jenkins runs as root, ~/rpmbuild
                # is /root/rpmbuild on a small root LV that fills up mid-build ("No space left on
                # device"). rpmbuild's _topdir defaults to $HOME/rpmbuild, so point HOME at the
                # tree just for this build; also send the compiler's temp files (normally /tmp,
                # also on root) to the same big filesystem via TMPDIR. BUILD/BUILDROOT/RPMS all
                # then land under ${_DIR}/rpmbuild, where the install step already looks first.
                mkdir -p "${_DIR}/rpmbuild/tmp"
                TMPDIR="${_DIR}/rpmbuild/tmp" HOME="${_DIR}" make -j"$(nproc)" binrpm-pkg
            fi
            '''
        }
    }
}

/**
 * Install the kernel package this build produced for `prefix` and return its release.
 *
 * The search is confined to the packaging output directory and the package name must carry
 * this build's LOCALVERSION: a kernel tree can ship distro packages of its own (e.g.
 * anolis/outputs/<n>/kernel-*.rpm) and a plain `find .` installs one of those instead, which
 * only surfaces much later as an empty PATCH_KERNEL / BASE_KERNEL.
 *
 * The release is read from the package file list — not from /boot mtimes, and not from the
 * RPM Version field, which rewrites the LOCALVERSION's '-' to '_' irreversibly.
 */
def installKernelFromBuild(String prefix) {
    String marker = "${env.WORKSPACE}/.installed_kernel_${prefix}"
    String pkgMarker = "${env.WORKSPACE}/.installed_pkg_${prefix}"

    withEnv([
        "_DIR=${env.DIR}",
        "_P=${prefix}",
        "_DEB=${env.BOOT_METHOD == 'grub_debian' ? '1' : '0'}",
        "_MARKER=${marker}",
        "_PKG_MARKER=${pkgMarker}",
        "_JRPM_DIR=${JENKINS_RPM_DIR}",
        "_CLEAR=${JENKINS_RPM_CLEARED ? '0' : '1'}"
    ]) {
            sh '''
            set -eu

        # A full /boot is what leaves dracut with a missing or truncated initramfs later
        # (see prepareBootArtifacts). List the kernels that could be pruned alongside it.
        df -h /boot || true
        FREE_MB=$(df -Pm /boot | awk 'NR==2 {print $4}')
        if [ -n "${FREE_MB}" ] && [ "${FREE_MB}" -lt 150 ]; then
            echo "WARNING: only ${FREE_MB} MB free on /boot; dracut may not manage an initramfs." >&2
            ls -1 /boot/vmlinuz-* >&2 || true
        fi

        if [ "${_DEB}" = 1 ]; then
            # bindeb-pkg writes one directory above the tree; -dbg is symbols, never bootable.
            # The package name uses the Debian-safe LOCALVERSION (underscore -> hyphen; see
            # buildKernel), so match on that hyphenated form.
            cd "${_DIR}/.."
            PDEB=$(printf '%s' "${_P}" | tr '_' '-')
            PKG=$(ls -t linux-image-*"${PDEB}"*.deb 2>/dev/null | grep -v -- '-dbg' | head -1 || true)
            if [ -z "${PKG}" ]; then
                echo "No linux-image .deb matching '${PDEB}' in $(pwd). Present:" >&2
                ls -t linux-image-*.deb 2>/dev/null || echo "  (none)"
                exit 1
            fi
            echo "Installing ${PKG}"
            dpkg -i "${PKG}"
            # Symlink lines end in the link target, not a package path.
            PKG_FILES=$(dpkg-deb -c "${PKG}" | awk '$1 !~ /^l/ {print $NF}' | sed -n 's|^\\.||p')
        else
            # `make binrpm-pkg` writes under rpmbuild's _topdir -- the build tree's rpmbuild on
            # some kernels, but $HOME/rpmbuild (e.g. /root/rpmbuild when built as root) on
            # others. Search BOTH and take the NEWEST match by mtime across all of them: since
            # this build just produced it, the newest is always this run's package (a stale
            # copy left in the other dir is older, so it is never chosen). The glob is anchored
            # to this build's LOCALVERSION ('${_P}') and excludes devel/headers/debug, so it can
            # only ever match a kernel package from THIS build -- not a distro kernel or any
            # other package that happens to live under rpmbuild.
            PKG=$(ls -t "${_DIR}/rpmbuild/RPMS"/*/kernel-[0-9]*"${_P}"*.rpm \
                        "${HOME}/rpmbuild/RPMS"/*/kernel-[0-9]*"${_P}"*.rpm \
                        /root/rpmbuild/RPMS/*/kernel-[0-9]*"${_P}"*.rpm 2>/dev/null \
                   | grep -v -E 'devel|headers|debug' | head -1 || true)
            if [ -z "${PKG}" ]; then
                echo "No kernel RPM matching '${_P}' under ${_DIR}/rpmbuild/RPMS, ${HOME}/rpmbuild/RPMS or /root/rpmbuild/RPMS. Built packages:" >&2
                ls -t "${_DIR}/rpmbuild/RPMS"/*/*.rpm "${HOME}/rpmbuild/RPMS"/*/*.rpm /root/rpmbuild/RPMS/*/*.rpm 2>/dev/null || echo "  (none)"
                exit 1
            fi
            echo "Installing ${PKG}"
            rpm -ivh "${PKG}" --force
            PKG_FILES=$(rpm -qlp "${PKG}" 2>/dev/null)
        fi

        RELEASE=$(printf '%s\\n' "${PKG_FILES}" | sed -n 's|^/boot/vmlinuz-||p' | head -1)
        if [ -z "${RELEASE}" ]; then
            RELEASE=$(printf '%s\\n' "${PKG_FILES}" | sed -n 's|^/lib/modules/\\([^/]*\\)/.*|\\1|p' | head -1)
        fi
        if [ -z "${RELEASE}" ]; then
            echo "ERROR: ${PKG} ships no kernel image; it is not a kernel package." >&2
            exit 1
        fi

        # rpm and dpkg both exit 0 when a post-install script bails out part-way through,
        # so confirm the payload actually landed.
        MISSING=0
        for f in $(printf '%s\\n' "${PKG_FILES}" | grep '^/boot/' || true); do
            if [ ! -e "$f" ]; then
                echo "MISSING after install: $f" >&2
                MISSING=1
            fi
        done
        if [ ! -d "/lib/modules/${RELEASE}" ]; then
            echo "MISSING after install: /lib/modules/${RELEASE}" >&2
            MISSING=1
        fi
        if [ "${MISSING}" != 0 ]; then
            echo "ERROR: ${PKG} did not deliver all of its files; see above." >&2
            exit 1
        fi

        # Linux 6.5+ kernel.spec ships the image as /lib/modules/<rel>/vmlinuz and leaves the
        # /boot copy to kernel-install, which does not always run on these distros.
        if [ ! -e "/boot/vmlinuz-${RELEASE}" ] && [ -e "/lib/modules/${RELEASE}/vmlinuz" ]; then
            echo "Post-install did not copy the image into /boot; copying it."
            cp -f "/lib/modules/${RELEASE}/vmlinuz" "/boot/vmlinuz-${RELEASE}"
        fi
        if [ ! -e "/boot/vmlinuz-${RELEASE}" ]; then
            echo "ERROR: /boot/vmlinuz-${RELEASE} missing after installing ${PKG}." >&2
            ls -1 /boot/vmlinuz-* >&2 || true
            exit 1
        fi
        ls -l "/boot/vmlinuz-${RELEASE}"

        echo "Installed kernel release: ${RELEASE}"
        printf '%s' "${RELEASE}" > "${_MARKER}"

        # Save this build's package under JENKINS_RPM_DIR so a later VM run can copy it into the
        # current-OS guest and install it. The dir is cleared ONCE per run (the first build),
        # then both builds' packages accumulate; a rebuild replaces the previous run's contents.
        mkdir -p "${_JRPM_DIR}"
        if [ "${_CLEAR}" = 1 ]; then
            echo "Clearing ${_JRPM_DIR} (replacing with this run's built packages)."
            rm -f "${_JRPM_DIR}"/* 2>/dev/null || true
        fi
        cp -f "${PKG}" "${_JRPM_DIR}/"
        SAVED_PKG="${_JRPM_DIR}/$(basename "${PKG}")"
        echo "Saved built package: ${SAVED_PKG}"
        printf '%s' "${SAVED_PKG}" > "${_PKG_MARKER}"
        '''
    }

    // The first build clears JENKINS_RPM_DIR; mark it done so the second build keeps both.
    JENKINS_RPM_CLEARED = true

    String release = readFile(marker).trim()
    if (!release) {
        error("installKernelFromBuild(${prefix}): the package reported no kernel release.")
    }
    INSTALLED_KERNELS[prefix] = release

    String savedPkg = fileExists(pkgMarker) ? readFile(pkgMarker).trim() : ''
    if (savedPkg) {
        BUILT_PKG_PATHS[prefix] = savedPkg
        echo "Built package for ${prefix} saved to: ${savedPkg}"
    }

    echo "Kernel release installed for ${prefix}: ${release}"
    return release
}

/**
 * Kernel release for `prefix`, as reported by the package installKernelFromBuild installed.
 *
 * Falls back to the newest /boot/vmlinuz-*<prefix>* by mtime for kernels this run did not
 * install. Errors out instead of returning an empty string: an empty PATCH_KERNEL /
 * BASE_KERNEL only fails several stages later, far from the install that went wrong.
 */
def getInstalledKernel(prefix) {
    if (INSTALLED_KERNELS[prefix]) {
        return INSTALLED_KERNELS[prefix]
    }

    // On Debian the installed release carries the hyphenated LOCALVERSION (with-patch),
    // since '_' is illegal in a package name; match that form in the /boot fallback glob.
    String pat = (env.BOOT_METHOD == 'grub_debian') ? prefix.replace('_', '-') : prefix

    // Newest mtime, not version-sorted: sort -V is alphabetical for git-hash suffixes
    // and can pick a stale kernel from a previous run.
    def release = sh(
        script: """
        ls -t /boot/vmlinuz-*${pat}* 2>/dev/null | head -1 | sed 's|/boot/vmlinuz-||'
        """,
        returnStdout: true
    ).trim()

    if (!release) {
        sh 'set -eu; echo "Kernels present in /boot:"; ls -t /boot/vmlinuz-* || true'
        error("No /boot/vmlinuz-*${prefix}* on the agent: the ${prefix} kernel package was not installed (see the install step above).")
    }
    return release
}

/** Runs the execute_<workload>.sh that split-job generated for one workload. */
def runWorkloadScript(String workload) {
    withEnv(["_W=${workload}"]) {
        sh '''
        set -eu
        echo "Running ${_W}"
        cd "${BUILD_HOME}/lkp-tests"
        "./execute_${_W}.sh"
        '''
    }
}

def Lkp_test(String lkpReportContext = 'host') {
    sh '''
    set -eu
    cd "${BASE_DIR}"

    # The directory existing is not enough: a prior run may have cloned it and then had
    # `make` fail, leaving lkp-tests unbuilt. Key off a yaml the next block needs.
    NEED_SETUP=0
    if [ ! -d "LKP_Automated" ] || [ ! -f "LKP_Automated/lkp-tests/jobs/hackbench.yaml" ]; then
        NEED_SETUP=1
    fi

    # Benchmark binaries must also be built/installed by `make`; the repo can be present while
    # the workloads are not. hackbench lands on PATH (e.g. /usr/local/bin/hackbench); ebizzy is
    # installed under /lkp/benchmarks. Missing either means we clone + make again.
    if ! command -v hackbench >/dev/null 2>&1; then
        echo "hackbench not installed (not on PATH); will clone + make."
        NEED_SETUP=1
    fi
    if [ ! -x /lkp/benchmarks/ebizzy/ebizzy ]; then
        echo "ebizzy not installed (/lkp/benchmarks/ebizzy/ebizzy missing); will clone + make."
        NEED_SETUP=1
    fi

    if [ "$NEED_SETUP" = 1 ]; then
        rm -rf LKP_Automated
        git clone https://github.com/GirishP789/LKP_Automated.git
        cd LKP_Automated
        echo "2" | make
    else
        echo "LKP_Automated present and hackbench + ebizzy already installed; skipping clone + make."
    fi
    '''

    withEnv([
        "_HACK_ITER=${params.HACKBENCH_ITERATIONS}",
        "_EBIZZY_ITER=${params.EBIZZY_ITERATIONS}"
    ]) {
        sh '''
        set -eu
        cd ${BUILD_HOME}/lkp-tests/jobs

        sed -i 's/^[[:space:]]*- 1600%/  # - 1600%/' hackbench.yaml
        sed -i 's/^[[:space:]]*# - 50%/  - 50%/' hackbench.yaml

        # Collapse `iterations:` to the SINGLE selected value. The yaml ships a matrix
        # (`iterations: 4 8`) and split-job expands one job per value, so any leftover value
        # runs too. Matching the whole line keeps this idempotent across runs.
        sed -i -E "s/^([[:space:]]*)iterations:.*/\\1iterations: ${_HACK_ITER}/" hackbench.yaml

        # ebizzy values carry an `x` suffix, e.g. `iterations: 100x`.
        sed -i -E "s/^([[:space:]]*)iterations:.*/\\1iterations: ${_EBIZZY_ITER}x/" ebizzy.yaml

        echo "Patched hackbench iterations -> ${_HACK_ITER}, ebizzy iterations -> ${_EBIZZY_ITER}x"
        '''
    }

    sh '''
    set -eu
    cd ${BUILD_HOME}/lkp-tests

    # split-job writes its expansions here and never cleans them up, so yesterday's
    # iteration selection would be picked up by the globs below and run forever. The dash
    # in the globs spares the templates, which live in ./jobs without one.
    rm -f hackbench-*.yaml ebizzy-*.yaml

    lkp split-job ./jobs/hackbench.yaml

    ls -l hack*yaml | awk '{print "lkp run " $9;}' | grep -v 1600 > execute_hackbench.sh
    chmod +x execute_hackbench.sh

    lkp split-job ./jobs/ebizzy.yaml

    ls -l ebizzy*yaml | awk '{ print "lkp run " $9;}' > execute_ebizzy.sh
    chmod +x execute_ebizzy.sh
    '''

    if (runsUnixbench()) {
        sh '''
        set -eu
        cd ${BUILD_HOME}/lkp-tests

        # unixbench.yaml is used as shipped — no iterations to patch. Same stale-output
        # handling as above; the `-1` keeps the glob to split output (unixbench-100%-...)
        # so unixbench-2d.yaml and unixbench-cgroup2.yaml survive.
        rm -f unixbench-1*.yaml

        lkp split-job ./jobs/unixbench.yaml

        # Only split output belongs in the script; the greps keep the templates out even
        # if a copy of one shows up here.
        ls -1 unixbench-*.yaml 2>/dev/null |
            grep -v -x -F 'unixbench-2d.yaml' |
            grep -v -x -F 'unixbench-cgroup2.yaml' |
            awk '{print "lkp run " $1;}' > execute_unixbench.sh
        chmod +x execute_unixbench.sh

        echo "execute_unixbench.sh jobs: $(wc -l < execute_unixbench.sh)"
        '''
    }

    // Guard against orphans from a previous run that was aborted/killed before its post
    // cleanup could run: kill any leftover host-side LKP workloads before we start, so they
    // cannot compete for CPU and skew this run's numbers.
    killHostLkpWorkloads()

    // Clear every workload result dir up front, before any workload runs. The report
    // block below scans /lkp/result/<workload>/ unconditionally, so partial selections
    // (e.g. WORKLOAD_TYPE=hackbench) would otherwise pick up the previous run's ebizzy
    // results and silently append them under the current run's timestamp.
    sh '''
    set -eu
    rm -rf /lkp/result/hackbench/* /lkp/result/ebizzy/* /lkp/result/unixbench/* 2>/dev/null || true
    '''

    if (runsHackbench()) {
        runWorkloadScript('hackbench')
    }
    if (runsEbizzy()) {
        runWorkloadScript('ebizzy')
    }
    if (runsUnixbench()) {
        runWorkloadScript('unixbench')
    }

    // Fill the Elves-style LKP result workbook (one file per JIRA/OS/kernel/pipe under /home/amd,
    // copied from LKP_RESULT_TEMPLATE_PATH on first use, edited in place after). This run writes
    // into ONE data column chosen by report context + booted kernel role (see excelColumnForRun):
    // host base/patch -> C/D, VM base/patch -> E/F, LKP-in-VM base/patch -> G/H. No CSV is produced.
    // The edit is SURGICAL (rewrites only the worksheet XML with stdlib zipfile) so the template's
    // chart, sensitivity label and shared strings are preserved and Excel opens it WITHOUT a
    // "repair" prompt -- openpyxl (which rewrites the whole workbook) mangles those.
    withEnv([
        "_COL=${excelColumnForRun(lkpReportContext)}",
        "_JIRA=${params.JIRA_ID ?: ''}",
        "_PIPE=${env.JOB_BASE_NAME ?: 'lkp'}",
        "_TEMPLATE=${params.LKP_RESULT_TEMPLATE_PATH}",
        "_HACK_ITER=${params.HACKBENCH_ITERATIONS}",
        "_EBIZZY_ITER=${params.EBIZZY_ITERATIONS}",
        "_RUN_UNIXBENCH=${runsUnixbench() ? '1' : '0'}",
        "_OUT_DIR=/home/amd"
    ]) {
        sh '''
        set -eu

        # ---- host facts for the workbook name + header rows (hostnamectl + lscpu) ----
        OS_FULL=$(hostnamectl 2>/dev/null | sed -n 's/^[[:space:]]*Operating System:[[:space:]]*//p' | head -1)
        [ -n "${OS_FULL}" ] || OS_FULL=$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown}")
        OS_SHORT=$(printf '%s' "${OS_FULL}" | awk '{print $1}')
        HOSTNAME_S=$(hostnamectl 2>/dev/null | sed -n 's/^[[:space:]]*Static hostname:[[:space:]]*//p' | head -1)
        [ -n "${HOSTNAME_S}" ] || HOSTNAME_S=$(hostname)
        KMAJMIN=$(uname -r | grep -oE '^[0-9]+\\.[0-9]+' | head -1)
        MODEL=$(lscpu | sed -n 's/^[[:space:]]*Model name:[[:space:]]*//p' | head -1)
        CPUFAM=$(lscpu | sed -n 's/^[[:space:]]*CPU family:[[:space:]]*//p' | head -1)
        SOCKETS=$(lscpu | sed -n 's/^[[:space:]]*Socket(s):[[:space:]]*//p' | head -1)
        CORES=$(lscpu | sed -n 's/^[[:space:]]*Core(s) per socket:[[:space:]]*//p' | head -1)
        NODES=$(lscpu | sed -n 's/^[[:space:]]*NUMA node(s):[[:space:]]*//p' | head -1)

        SAFE_OS=$(printf '%s' "${OS_SHORT}" | tr ' /' '__')
        SAFE_PIPE=$(printf '%s' "${_PIPE}" | tr ' /' '__')

        # Per-JIRA workbook directory: /home/amd/DEAE_<JIRA_ID>/ (created if absent; reused if
        # already present). The workbook lives inside it -- copied from the template on first
        # use, then edited in place on later runs.
        WB_DIR="${_OUT_DIR}/DEAE_${_JIRA}"
        mkdir -p "${WB_DIR}"
        XLSX="${WB_DIR}/DEAE_${_JIRA}_${SAFE_OS}_${KMAJMIN}_${SAFE_PIPE}.xlsx"

        if [ ! -f "${XLSX}" ]; then
            [ -f "${_TEMPLATE}" ] || { echo "LKP_RESULT_TEMPLATE_PATH not found: ${_TEMPLATE}" >&2; exit 1; }
            cp -f "${_TEMPLATE}" "${XLSX}"
            echo "Created ${XLSX} from template ${_TEMPLATE}"
        else
            echo "Editing existing ${XLSX}"
        fi

        PYTOOL=$(mktemp /tmp/lkp_xlsx_XXXXXX.py)
        cat > "${PYTOOL}" <<'PY'
import os, re, glob, zipfile, shutil

xlsx = os.environ["XLSX"]
col  = os.environ.get("COL", "").strip()
run_unix = os.environ.get("RUN_UNIXBENCH", "0")
hi = os.environ["HACK_ITER"]

# Rows: hackbench 7-14 (by iteration), ebizzy 15, unixbench 16-43 (the template's fixed names).
hb = []
for kind in ("pipe", "socket"):
    for mode in ("process", "threads"):
        for pct in ("100%", "50%"):
            hb.append("%s-%s-%s-%s" % (kind, hi, mode, pct))
UNIX = ["arithoh", "context1", "dhry2reg", "double", "execl", "float", "fsbuffer",
        "fsbuffer-r", "fsbuffer-w", "fsdisk", "fsdisk-r", "fsdisk-w", "fstime", "fstime-r",
        "fstime-w", "grep", "hanoi", "int", "long", "pipe", "shell1", "shell16", "shell8",
        "short", "spawn", "syscall", "sysexec", "whetstone-double"]

name2row = {}
for i, n in enumerate(hb):
    name2row[n] = 7 + i
for i, n in enumerate(UNIX):
    name2row["100%-300s-" + n] = 16 + i

# Cells to write: ref -> (value, is_text). Headers + labels always; data only if a column resolved.
writes = {}
writes["A1"] = ("LKP Test Results - %s - Kernel %s - %s" % (
    os.environ["OS_FULL"], os.environ["KMAJMIN"], os.environ["PIPE"]), True)
writes["A2"] = (("HOST DETAILS:  Host Name: %s, Model name: %s, CPU family: %s, "
                 "Sockets/Core: %s / %s, Nodes: %s") % (
    os.environ["HOSTNAME_S"], os.environ["MODEL"], os.environ["CPUFAM"],
    os.environ["SOCKETS"], os.environ["CORES"], os.environ["NODES"]), True)
for i, n in enumerate(hb):
    writes["B%d" % (7 + i)] = (n, True)
writes["B15"] = ("ebizzy-10s-%sx-200%%.yaml" % os.environ["EBIZZY_ITER"], True)

def elapsed(tf):
    try:
        with open(tf, errors="replace") as f:
            for ln in f:
                if "Elap" in ln:
                    toks = [t for t in ln.split() if ":" in t]
                    if not toks:
                        return None
                    parts = [p.replace("h", "") for p in toks[-1].strip("(),").split(":")]
                    try:
                        if len(parts) == 3:
                            s = int(parts[0]) * 3600 + float(parts[1]) * 60 + float(parts[2])
                        elif len(parts) == 2:
                            s = int(float(parts[0])) * 60 + float(parts[1])
                        else:
                            return None
                    except ValueError:
                        return None
                    return int(s + 0.5)
    except Exception:
        return None
    return None

def test_dir(tf, workload):
    parts = tf.split("/")
    for i, p in enumerate(parts):
        if p == workload and i + 1 < len(parts):
            return parts[i + 1]
    return None

if col:
    workloads = [("hackbench", "hackbench.time"), ("ebizzy", "ebizzy.time")]
    if run_unix == "1":
        workloads.append(("unixbench", "unixbench.time"))
    for wl, tfn in workloads:
        for tf in sorted(glob.glob("/lkp/result/%s/**/%s" % (wl, tfn), recursive=True)):
            secs = elapsed(tf)
            if secs is None:
                continue
            td = test_dir(tf, wl)
            row = 15 if wl == "ebizzy" else name2row.get(td or "")
            if not row:
                print("  no template row for %s test_dir=%s" % (wl, td))
                continue
            writes["%s%d" % (col, row)] = (secs, False)
    print("Data column: %s" % col)
else:
    print("No column resolved (role/context); writing headers + labels only.")

# ---- surgical edit: rewrite ONLY the worksheet XML so the template's chart, sensitivity label
# and shared strings survive byte-for-byte (openpyxl would drop/mangle them -> Excel "repair").
# Every target cell already exists in the template, so replace it in place (keeping its style s).
SHEET = "xl/worksheets/sheet1.xml"
with zipfile.ZipFile(xlsx) as z:
    names = z.namelist()
    if SHEET not in names:
        SHEET = next((n for n in names if re.match(r"xl/worksheets/sheet\\d+\\.xml$", n)), SHEET)
    xml = z.read(SHEET).decode("utf-8")

def esc(v):
    return str(v).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

def set_cell(xml, ref, val, is_text):
    if val is None:
        return xml
    m = re.search(r'<c r="%s"([^>]*?)(/>|>.*?</c>)' % re.escape(ref), xml, re.DOTALL)
    if not m:
        print("  MISSING template cell %s" % ref)
        return xml
    sm = re.search(r'\\ss="(\\d+)"', m.group(1))
    s = ' s="%s"' % sm.group(1) if sm else ''
    if is_text:
        cell = '<c r="%s"%s t="inlineStr"><is><t xml:space="preserve">%s</t></is></c>' % (ref, s, esc(val))
    else:
        cell = '<c r="%s"%s><v>%s</v></c>' % (ref, s, val)
    return xml[:m.start()] + cell + xml[m.end():]

for ref, (val, is_text) in writes.items():
    xml = set_cell(xml, ref, val, is_text)

# Force Excel to recalculate every formula on open. The Delta% (I/J/K) and summary cells
# depend on the C-H data we just injected; because we rewrite the worksheet XML directly (not
# via Excel) their cached results stay stale, so without fullCalcOnLoad Excel would show the
# template's empty cached Delta% instead of recomputing.
try:
    with zipfile.ZipFile(xlsx) as _z:
        wbxml = _z.read("xl/workbook.xml").decode("utf-8")
    if "fullCalcOnLoad" not in wbxml:
        if re.search(r'<calcPr[^>]*/>', wbxml):
            wbxml = re.sub(r'<calcPr([^>]*)/>', lambda m: '<calcPr' + m.group(1) + ' fullCalcOnLoad="1"/>', wbxml, count=1)
        elif "<calcPr" in wbxml:
            wbxml = re.sub(r'<calcPr([^>]*)>', lambda m: '<calcPr' + m.group(1) + ' fullCalcOnLoad="1">', wbxml, count=1)
        else:
            wbxml = wbxml.replace("</workbook>", '<calcPr calcId="191028" fullCalcOnLoad="1"/></workbook>')
except Exception:
    wbxml = None

tmp = xlsx + ".tmp"
with zipfile.ZipFile(xlsx) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        data = zin.read(it.filename)
        if it.filename == SHEET:
            data = xml.encode("utf-8")
        elif it.filename == "xl/workbook.xml" and wbxml is not None:
            data = wbxml.encode("utf-8")
        zout.writestr(it, data)
shutil.move(tmp, xlsx)
print("Wrote %d cell(s) to %s" % (len(writes), xlsx))
PY

        XLSX="${XLSX}" COL="${_COL}" OS_FULL="${OS_FULL}" KMAJMIN="${KMAJMIN}" PIPE="${_PIPE}" \
        HOSTNAME_S="${HOSTNAME_S}" MODEL="${MODEL}" CPUFAM="${CPUFAM}" SOCKETS="${SOCKETS}" \
        CORES="${CORES}" NODES="${NODES}" HACK_ITER="${_HACK_ITER}" EBIZZY_ITER="${_EBIZZY_ITER}" \
        RUN_UNIXBENCH="${_RUN_UNIXBENCH}" python3 "${PYTOOL}"
        rm -f "${PYTOOL}"
        echo "LKP results written to ${XLSX} (column ${_COL:-none})"
        '''
    }

    backupLkpResults(lkpReportContext)
}
