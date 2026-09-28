# Prerequisites Setup — AI Agent Prompt for `lkp_6os_kinstall.groovy`

This file contains a **ready-to-paste prompt** for an AI agent (e.g. Cursor / Claude) that will
connect to a target SUT, then **install and verify** every prerequisite the pipeline
`Rollback_jenkins/LKP/lkp_6os_kinstall.groovy` assumes but does **not** set up itself.

The pipeline already auto-installs the kernel build toolchain, `python3-pip`,
`python3-pyfiglet`/`openpyxl` (or pip `pyfiglet`), `sshpass`, and clones+builds LKP
(`hackbench`/`ebizzy`). Everything in the prompt below is the remaining, host-level setup.

---

## How to use

1. Open a fresh AI agent chat that can run a shell / SSH.
2. Paste the entire **PROMPT** block below.
3. The agent will first ask you for the target **host IP**, the **root password**, and the
   **amd password**, then do the install + checks and give you a final PASS/ACTION report.

---

## PROMPT (copy everything between the lines)

---

You are a Linux setup assistant. Your job is to prepare and verify a single SUT so it can run
the Jenkins pipeline `lkp_6os_kinstall.groovy` (LKP kernel-regression: builds/boots base+patch
kernels, spawns per-OS KVM VMs from golden qcow2 images, installs a kernel into the current-OS
guest, runs LKP, and writes an Excel report).

### Step 0 — ASK FIRST (do not proceed until you have all three)
Ask the user for, and wait for:
1. **Target host IP** (the SUT / Jenkins agent).
2. **root password** for that host.
3. **amd user password** for that host.

Also ask (optional, used only by the Step 5 test VM login): the **VM guest username/password**
stored in the Jenkins credential `VM_LOGIN_CREDENTIALS_ID`. If the user does not provide it,
Step 5 still verifies that the VM boots and gets an IP, and marks the SSH login as CANNOT-VERIFY.

Connect over SSH. Prefer `root` for installs. Use `amd` only to verify the *agent user's* view
(groups, read access). Never print the passwords back. If a login fails, stop and report it.

### Step 1 — Identify the host
- Run `whoami`, `hostname`, `hostname -I`, `cat /etc/os-release` (capture `ID`/`PRETTY_NAME`),
  `uname -r`, `nproc`, and free memory.
- Decide the package manager: `dnf`/`yum` for anolis/Rocky/Opencloud/Euler (RHEL-family),
  `apt-get` for Velinux/Ubuntu (Debian-family).
- Confirm the OS is one of: **anolis, Rocky, Opencloud, Euler, Velinux, Ubuntu**. If not, warn
  the user (the pipeline's OS detection and per-OS guest selection expect one of these).

### Step 2 — INSTALL (as root)
1. **Java 21** (needed for the Jenkins agent). Try the distro package first
   (`java-21-openjdk-devel` on RHEL-family, `openjdk-21-jdk` on Debian-family). If the distro
   has no Java 21, download **Temurin 21** from Adoptium and install under `/opt`, then register
   it with `alternatives` and set it default. Verify `java -version` reports **21**.
2. **Virtualization stack** (the pipeline uses these but never installs them):
   - RHEL-family: `dnf install -y qemu-kvm libvirt virt-install libguestfs-tools`
   - Debian-family: `apt-get install -y qemu-kvm libvirt-daemon-system virtinst`
3. **Enable + start libvirt:** `systemctl enable --now libvirtd` (if that unit is absent, enable
   the modular daemons `virtqemud`/`virtnetworkd` and their sockets).
4. **libvirt default network:** ensure it is defined, active, and autostart:
   - `virsh net-list --all`
   - if not active: `virsh net-start default`
   - `virsh net-autostart default`
   - If `net-start default` fails with an iptables/REJECT error, tell the user the **running
     kernel is missing the netfilter REJECT target** (`CONFIG_IP_NF_TARGET_REJECT`/`xt_REJECT`)
     and the default network cannot start until a kernel with it is booted.

### Step 3 — CHECK (report OK / MISSING / FIX-APPLIED for each)
1. **/dev/kvm** exists and hardware virtualization is enabled (`ls -l /dev/kvm`; check `kvm`
   module and `kvm_amd`/`kvm_intel` loaded). If missing, warn that VT/SVM must be enabled in BIOS.
2. **Tools on PATH:** `virsh`, `qemu-img`, `virt-install`, and an emulator
   (`/usr/libexec/qemu-kvm` or `/usr/bin/qemu-system-x86_64`). Report any missing.
3. **default network active** (from Step 2) and able to hand out DHCP (`virsh net-info default`).
4. **Agent user `amd`** is in the `kvm` and `libvirt` groups (`id amd`). Only strictly required
   if the Jenkins agent will run as `amd`; note that the pipeline is written to run **as root**.
5. **Java default is 21** for both `root` and `amd` (`sudo -u amd bash -lc 'java -version'`).
6. **Boot tooling** present: `grubby` (RHEL-family) OR `grub-reboot`/`grub-set-default` +
   `update-grub`/`grub-mkconfig` (Debian-family); and `dracut` or `update-initramfs`/`mkinitramfs`.
7. **Internet reachability** from the host: `github.com` (LKP clone), the distro repos, and
   `pypi.org` (for pip pyfiglet). Report if a proxy is likely needed.
8. **Disk space:** `/vms`, `/tests`, and `/home/amd` each have room (goldens are ~20 GB each;
   overlays and result archives accumulate).

### Step 4 — FILE / PATH CHECKS (notify the user; do NOT fabricate anything)
**Do NOT create any directory in this step.** `/vms` and `/tests` are separate hardware disks
that must be mounted by the user. If a path below is missing, report it as MISSING and tell the
user to mount or create it. Never run `mkdir` under `/vms` or `/tests`.

0. **Disk mounts:** check that `/vms` and `/tests` are mounted (`findmnt /vms`, `findmnt /tests`,
   or `mountpoint /vms` / `mountpoint /tests`; also show `lsblk` and `df -h /vms /tests`).
   If either one is not a mount point, **notify the user**: "`/vms` (or `/tests`) is not mounted;
   please mount the hardware disk". Mark every item below that lives on that disk as
   MISSING (mount needed), and skip Step 5 if `/vms` is not mounted.
1. **12 golden VM qcow2 images** must exist in **`/vms/jenkins_qcow2/`** and be owned `qemu:qemu`:
   - no-LKP (6): `anolis_jenkins_vm.qcow2`, `Rocky_jenk_vm.qcow2`, `Opencloud_jenk_VM.qcow2`,
     `Euler_jenk_vm.qcow2`, `Velinux_jenk_vm.qcow2`, `Ubuntu_jenk_vm.qcow2`
   - LKP (6): `anolis_jen_lkp_vm.qcow2`, `Rocky_jen_lkp_vm.qcow2`, `Opencloud_jen_lkp_vm.qcow2`,
     `Euler_jen_lkp_vm.qcow2`, `Velinux_jen_lkp_vm.qcow2`, `Ubuntu_jen_lkp_vm.qcow2`
   - If `/vms/jenkins_qcow2/` does not exist, do not create it: notify the user that the
     directory is not present (check that `/vms` is mounted). Otherwise list which images are
     present and which are **MISSING**. If any are missing, **notify the user** and give the copy command
     from the development host (do not invent images):
     ```
     scp amd@10.86.26.102:/home/amd/vol1/images/jenkins/*  /vms/jenkins_qcow2/
     chown qemu:qemu /vms/jenkins_qcow2/*
     ```
2. **LKP result Excel template** at **`/vms/jenkins_excel_template/lkp_result_template.xlsx`**.
   Do not create the directory. If `/vms/jenkins_excel_template/` or the file is **MISSING**,
   notify the user and give:
   ```
   scp amd@10.86.26.102:/home/amd/vol1/images/jenkins_excel_template/lkp_result_template.xlsx  /vms/jenkins_excel_template/
   ```
3. **Result-archive root** **`/tests/jenkins/workspace/`** must exist and be writable
   (per-build subdirs are created by the pipeline). Do not create it: if it is missing, notify
   the user that the directory is not present (check that `/tests` is mounted). If it exists,
   confirm it is writable (for example `test -w /tests/jenkins/workspace`).
4. **Kernel source (only if `RPM_KERNEL_BUILD=BUILD_BOTH` will be used):** the `EXISTING_REPO_PATH`
   git checkout must exist on the agent on the intended branch — ask the user for the path and
   verify it is a git working tree.

### Step 5 - TEST VM: create one VM and verify it (as root)
Only run this step if Steps 2-4 are OK for KVM, libvirt, the default network, and at least the
golden image used below. It runs the same flow as the pipeline (qcow2 overlay on top of a golden
image, `virt-install --import`, DHCP lease on `default`, SSH login), but with a single small
throwaway VM. The golden image is never modified.

1. **Pick the golden** that matches the host OS (from Step 1), from the no-LKP family in
   `/vms/jenkins_qcow2/`: anolis -> `anolis_jenkins_vm.qcow2`, Rocky -> `Rocky_jenk_vm.qcow2`,
   Opencloud -> `Opencloud_jenk_VM.qcow2`, Euler -> `Euler_jenk_vm.qcow2`,
   Velinux -> `Velinux_jenk_vm.qcow2`, Ubuntu -> `Ubuntu_jenk_vm.qcow2`.
   If that file is missing, use any golden that is present and say so in the report.
2. **Create the VM:**
   ```
   IMG_DIR=/vms/jenkins_qcow2
   BASE=${IMG_DIR}/<golden from item 1>
   VM=prereq_testVM
   DISK=${IMG_DIR}/${VM}.qcow2
   virsh destroy ${VM} 2>/dev/null; virsh undefine ${VM} --nvram 2>/dev/null || virsh undefine ${VM} 2>/dev/null
   rm -f ${DISK}
   id qemu >/dev/null 2>&1 && chown qemu:qemu ${BASE}
   chmod 0644 ${BASE}; chmod o+rx ${IMG_DIR}
   qemu-img create -f qcow2 -b ${BASE} -F qcow2 ${DISK}
   id qemu >/dev/null 2>&1 && { chown qemu:qemu ${DISK}; chmod 0660 ${DISK}; }
   virt-install --name ${VM} --memory 4096 --vcpus 2 --cpu host \
     --disk path=${DISK},format=qcow2,bus=virtio --import \
     --network network=default,model=virtio --graphics vnc --video vga \
     --osinfo detect=on,require=off --noautoconsole
   ```
3. **Verify it is running:** `virsh domstate prereq_testVM` must report `running`. If
   `virt-install` failed, report the exact error. Common causes are "Permission denied" on the
   golden or its directory, a missing emulator, or `/dev/kvm` being absent.
4. **Verify it gets an IP** (wait up to 180 s, polling every 5 s):
   ```
   MAC=$(virsh domiflist prereq_testVM | awk '/network/ {print $5; exit}')
   virsh net-dhcp-leases default | awk -v m="$MAC" 'tolower($0) ~ tolower(m) {print $5}' | cut -d/ -f1
   ```
   No lease within 180 s means the default network or DHCP is broken: report it as MISSING.
5. **Verify SSH login** (only if the guest credentials were provided in Step 0). Install
   `sshpass` if absent, then wait up to 180 s for:
   ```
   SSHPASS='<guest password>' sshpass -e ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
     -o ConnectTimeout=5 <guest user>@<IP> 'hostname; uname -r; sudo -n true && echo SUDO_OK'
   ```
   Report the guest hostname and kernel. The pipeline runs `sudo` inside the guest, so report
   whether passwordless sudo works (`SUDO_OK`).
6. **Capacity note:** the pipeline creates each VM with **33000 MB RAM and 16 vCPUs**, 6 per-OS
   VMs plus up to 5 extra. From `nproc` and free memory (Step 1), report how many such VMs the host
   can hold, and warn if it is fewer than the planned VM count.
7. **Always clean up**, even if a check failed:
   ```
   virsh destroy prereq_testVM 2>/dev/null
   virsh undefine prereq_testVM --nvram 2>/dev/null || virsh undefine prereq_testVM 2>/dev/null
   rm -f /vms/jenkins_qcow2/prereq_testVM.qcow2
   ```
   Confirm `virsh list --all` no longer shows it and the golden image is still present.

### Step 6 — FINAL REPORT
Print a summary table with one row per item above and a status of **OK**, **INSTALLED/FIXED**,
**MISSING (action needed)**, or **CANNOT-VERIFY**, followed by a short bullet list of the exact
actions the user still has to take (especially any missing qcow2 images and the missing template).
Do not mark the host "ready" unless every Step 2–5 item is OK. The Step 5 SSH login may be
CANNOT-VERIFY if no guest credentials were given.

---

## Quick manual checklist (if you are doing it by hand instead of via the agent)

| Item | Command / check |
|---|---|
| Java 21 | `java -version` → 21 (install `java-21-openjdk-devel` / `openjdk-21-jdk`, or Temurin 21) |
| KVM/libvirt/virt-install | install `qemu-kvm libvirt virt-install`; `systemctl enable --now libvirtd` |
| /dev/kvm | `ls -l /dev/kvm` (enable VT/SVM in BIOS if absent) |
| default network | `virsh net-start default && virsh net-autostart default` |
| 12 goldens | `ls /vms/jenkins_qcow2/` → scp from `10.86.26.102` + `chown qemu:qemu` if missing |
| Excel template | `ls /vms/jenkins_excel_template/lkp_result_template.xlsx` → scp if missing |
| disk mounts | `findmnt /vms` and `findmnt /tests` (hardware disks; mount them if missing, never `mkdir`) |
| result root | `ls -d /tests/jenkins/workspace` (must exist and be writable; notify if missing) |
| test VM | overlay on the host-OS golden + `virt-install --import` -> `running`, DHCP IP, SSH login; then destroy/undefine + delete overlay |
| Jenkins agent | runs as **root**, Java 21, auto-reconnects after reboot |
| credential | `VM_LOGIN_CREDENTIALS_ID` set to a Jenkins username/password credential |
