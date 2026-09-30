# LKP_single_qcow2

LKP base vs patch kernel regression pipeline that runs the LKP benchmarks (hackbench, ebizzy,
unixbench) on a baseline and a patched kernel, on the host and inside `VM_COUNT` identical KVM
VMs cloned from a single pair of qcow2 images, and records the comparison in an Excel workbook.

## Pipeline

- `lkp_vmcount.groovy`

## Highlights

- Kernel source: build base + patch from a kernel tree (`BUILD_BOTH`), use preinstalled kernels
  (`MANUAL_BOTH_NO_BUILD`), or do nothing (`NULL_NO_KERNEL_WORKFLOW`).
- VMs: `VM_COUNT` clones from `VM_QCOW2_PATH_WITHOUT_LKP` / `VM_QCOW2_PATH_WITH_LKP`
  (default `/vms/jenkins_qcow2/anolis_jenkins_vm.qcow2` and `anolis_jen_lkp_vm.qcow2`).
- Test setups: host only, host + VMs as load, LKP on host and inside the VMs (`LKP_RUN_OPTIONS`).
- Results: Excel workbook in `/home/amd/DEAE_<JIRA_ID>/`; raw LKP results and a copy of the
  workbook in the Jenkins job workspace, `<job workspace>/Run_<build>/` (download from the
  build's Workspaces page).

## Before you run

- Two golden qcow2 images at the paths above (owned `qemu:qemu`).
- Excel template at `/vms/jenkins_excel_template/lkp_result_template.xlsx`.
- The Jenkins agent workspace (e.g. `/tests/jenkins/workspace`) is on a disk with free space.
- Jenkins agent runs as root with Java 21; KVM/libvirt with the `default` network active.
- Each VM uses 33 GB RAM and 16 vCPUs: size `VM_COUNT` to the host memory.
- Tick `PREREQUISITES_CONFIRMED` (the build fails if unticked); the agent must be online (the
  build fails after 1 minute otherwise). After each kernel reboot the pipeline waits up to 1 hour
  for the agent: on veLinux, update the node IP in Jenkins if it changed and relaunch the agent.
