# Jenkins_pipelines

Jenkins pipelines repository. Each pipeline lives on its own branch, created from `main`.
This file lists the existing branches and a one-line description of each.

## Branches

- **LKP_single_qcow2** - LKP base vs patch kernel regression that spawns `VM_COUNT` identical VMs from a single pair of qcow2 images (without-LKP / with-LKP)
- **LKP_6_qcow2** - LKP base vs patch kernel regression that spawns one VM per OS from 6 golden qcow2 images (anolis, Rocky, Opencloud, Euler, Velinux, Ubuntu) plus optional extra VMs
