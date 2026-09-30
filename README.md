# LKP_6_qcow2

LKP base vs patch kernel regression pipeline that runs the LKP benchmarks (hackbench, ebizzy,
unixbench) on a baseline and a patched kernel, on the host and inside one KVM VM per OS spawned
from 6 golden qcow2 images, and records the comparison in an Excel workbook.

## Pipeline

- `lkp_6os.groovy`

## Highlights

- Kernel source: build base + patch from a kernel tree (`BUILD_BOTH`), use preinstalled kernels
  (`MANUAL_BOTH_NO_BUILD`), or do nothing (`NULL_NO_KERNEL_WORKFLOW`).
- VMs: one guest per OS (anolis, Rocky, Opencloud, Euler, Velinux, Ubuntu) from the golden images
  in `/vms/jenkins_qcow2/`, plus 0-5 `EXTRA_VMS` cloned from the host-OS image.
- Test setups: host only, host + VMs as load, LKP on host and inside the VMs (`LKP_RUN_OPTIONS`).
- Results: Excel workbook in `/home/amd/DEAE_<JIRA_ID>/`; raw LKP results and a copy of the
  workbook in the Jenkins job workspace, `<job workspace>/Run_<build>/` (download from the
  build's Workspaces page).

## Before you run

- 12 golden qcow2 images (6 no-LKP + 6 LKP) in `/vms/jenkins_qcow2/` (owned `qemu:qemu`).
- Excel template at `/vms/jenkins_excel_template/lkp_result_template.xlsx`.
- The Jenkins agent workspace (e.g. `/tests/jenkins/workspace`) is on a disk with free space.
- Jenkins agent runs as root with Java 21; KVM/libvirt with the `default` network active.
- Each VM uses 33 GB RAM and 16 vCPUs: 6 VMs (+ extras) must fit in host memory.
- Tick `PREREQUISITES_CONFIRMED` (the build fails if unticked); the agent must be online (the
  build fails after 1 minute otherwise). After each kernel reboot the pipeline waits up to 1 hour
  for the agent: on veLinux, update the node IP in Jenkins if it changed and relaunch the agent.
