# vm-bridge: build the X++ model on the dev VM from this PC

The dev VM is reachable only over RDP. RDP can share this PC's C: drive into the session
(`\\tsclient\C`), and that shared folder is the whole channel: the PC drops a request file, a
script in the RDP session on the VM carries it out and writes the answer back. No ports are
opened and nothing is installed on the VM. It works only while the RDP session is open.

```
PC   C:\Claude\vm-bridge\requests\<id>.json   --->   VM  vm-watcher.ps1 (in the RDP session)
PC   C:\Claude\vm-bridge\results\<id>.json/.log <---      status | compare | deploy | build | restart | dbsync
```

## Once per RDP session

1. In the Remote Desktop client: **Show Options > Local Resources > More... > Drives**, tick **C:**.
   (Saved in the .rdp file after the first time.)
2. On the VM, open PowerShell **as administrator** and run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File \\tsclient\C\Claude\d365-mobile-ops\tools\vm-bridge\vm-watcher.ps1
   ```

   Leave the window open. It finds `PackagesLocalDirectory` on K:, C: or J: by itself; pass
   `-Packages <path>` if it lives elsewhere.

## From the PC

```powershell
tools\vm-bridge\vm.ps1 status                    # paths, services, admin rights
tools\vm-bridge\vm.ps1 compare                   # repo vs VM model files (line endings ignored)
tools\vm-bridge\vm.ps1 deploy AxClass/HSMobileOpsSkuPageService.xml
tools\vm-bridge\vm.ps1 build -TimeoutSec 1200    # xppc on the HSMobileOps model; errors listed
tools\vm-bridge\vm.ps1 restart                   # iisreset + batch service: load the new build
tools\vm-bridge\vm.ps1 dbsync                    # only when tables or enums changed
```

## What it will and won't do

- Only the six actions above. A request cannot run anything else.
- **deploy** copies only the files named, from the repo's `aos/HSMobileOps`, and backs up every
  file it replaces to `C:\Temp\vm-bridge-backups\<id>\` on the VM. It never mirrors or deletes,
  so objects that exist only on the VM (dev tools, work in progress) are left alone. Run
  **compare** first to see what differs.
- Sharing C: lets the RDP session read this PC's drive. Untick it when you don't need the bridge.
