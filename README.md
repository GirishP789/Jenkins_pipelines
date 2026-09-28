# End_to_End_LKP

End-to-end LKP (Linux Kernel Performance) base vs patch kernel regression package: the
pipeline, its host prerequisites, a flow chart, and a user manual.

## Files

| File | What it is |
|---|---|
| `lkp_6os_kinstall.groovy` | Jenkins declarative pipeline. Builds or uses base + patch kernels, boots each on the SUT, spawns one KVM VM per OS from 6 golden qcow2 images (plus optional extra VMs), installs the kernel package into the current-OS guest, runs LKP (hackbench / ebizzy / unixbench) on host and guests, and fills the Excel result report. |
| `prerequisites_installation.md` | Ready-to-paste AI-agent prompt (plus a manual checklist) that prepares and verifies a SUT: Java 21, KVM/libvirt, default network, disk mounts, golden images, Excel template, result folder, and a one-VM create/verify test. |
| `pipeline_flow.png` | Flow chart of the pipeline stages and the decision points for each `RPM_KERNEL_BUILD` / `LKP_RUN_OPTIONS` scenario. |
| `user_manual.pdf` | User manual with Jenkins screenshots: job setup, every build parameter, how to run each scenario, and where to find results and the Excel report. |

## Before you run

1. Prepare the SUT with `prerequisites_installation.md` (`/vms` and `/tests` must be mounted disks).
2. The 12 golden images must be in `/vms/jenkins_qcow2/` and the template at
   `/vms/jenkins_excel_template/lkp_result_template.xlsx`.
3. Add the SUT as a Jenkins agent (runs as root, Java 21), create the job from
   `lkp_6os_kinstall.groovy`, and set `VM_LOGIN_CREDENTIALS_ID`.
4. Tick `PREREQUISITES_CONFIRMED` on Build with Parameters.

Results: `/tests/jenkins/workspace/<JOB_NAME>/Run_<N>/` and
`/home/amd/DEAE_<JIRA_ID>/DEAE_<JIRA_ID>_<OS>_<kernel>_<pipe>.xlsx`.
