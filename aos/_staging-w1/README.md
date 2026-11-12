# Workspace W1: staged, not in the model

Built from `docs/workspace-plan.md` (W1). Nothing here is in `aos/HSMobileOps`, so it cannot affect the stock count session's deploys. Nothing has been compiled, imported or deployed. All 54 files parse as XML, that is the only check done.

## History
First import (2026-11-07) did not compile (`AxFormTileButtonControl` unknown) and was rolled back. That design counted form data sources, which is not how standard tiles work. This is the redesign, modelled on standard files read on the dev VM (read-only): `CostAdminWorkspace` form, `CostItemMissingConfigTile` (AxTile), the menu item `CostReleasedProductsMissingCostingData` (has a `Query`), query `CostItemsMissingCostingDataQuery`. A copy of the standard form is in the session scratchpad only.

## How a tile works
`AxQuery` (store scope + the tile's ranges) <- `AxMenuItemDisplay` (`Query` = that query, opens the existing list form) <- `AxTile` (Type Count, `MenuItemName`) <- tile button on the workspace form. The tile counts the query, and the list opens on the same query, so the number equals the rows. No code runs in the existing forms: their files are not touched, and the seven form extensions and the filter class of the first design are gone.

Store scope and time thresholds are static methods called inside the query range values (standard files do this, for example `(HcmWorkerLookup::currentWorker())`):
- `HSMobileOpsWorkspaceScope`: `isAllStores`, `warehouses`, `hasAnyStore`, `warehouseRange` and one method per table (`postQueue`, `deviceWork`, `countMaster`, `countWarehouse`, `device`, `feedback`, `users`). Admin and system administrator: an always-true expression. Others: their warehouses; no worker link: `#NOSTORE#` (nothing). Users tiles scope on the mobile user RecId list.
- `HSMobileOpsWorkspaceRange`: `stuckBefore` (15 min), `deviceBefore` (7 days), `lockedAfter`, `notEmpty`.

## What is here (`HSMobileOps/`, 54 files)
- `AxForm/HSMobileOpsWorkspace`: pattern TabbedWorkspace, Panorama tab, four tile sections (Receiving and posting, Counting, Devices and users, Feedback), 12 count tiles and the licence tile. `init` warns when a non-administrator has no store.
- `AxTile` (13): 12 Count tiles plus `HSMobileOpsWorkspaceLicenceTile` (Standard, opens the licence setup).
- `AxQuery` (13) `HSMobileOpsWorkspace<Key>Query`, `AxMenuItemDisplay` (14: workspace plus 13 drill-downs), two classes.
- `AxSecurityPrivilege` (6), `AxSecurityDuty/HSMobileOpsViewWorkspace`.
- Patched COPIES of `AxMenu/HSMobileOps`, `AxSecurityRole/HSMobileOpsAdministrator` and `HSMobileOpsViewer`. They overwrite live files if copied: re-apply the insertions to the live files instead (menu item first in the menu; the view duty in both roles).

## Deliberately not in W1
Control center scope change (after phase 2 settles; until then the Counting tiles are scoped but the control center list is not, only for the number-equals-rows check on a non-admin). Open cycle journals tile, dual count tiles, KPIs, Activity tab, freeze banner. A Viewer sees the licence tile but cannot open it (the licence setup is granted to the Administrator only).

## Verified against standard files
Tile XML (`AxTile`: Name, ApplyFilter, MenuItemName, Size, Type), menu item with `Query`, query shape (root data source, Fields, Ranges), the form layout and the `TileButtonControl` extension, range values that call static methods and use `currentUserId()`.

## Still unverified: check in this order
1. The compile. Likely first failures: query XML element order or the range element (`Name`, `Field`, `Value`), `AxTile` `Type` value `Standard` for the licence tile, the `TabbedWorkspace` pattern with a non-standard tab page set.
2. A static method used as a whole range value returning a plain list (`WH1,WH2`) or an expression (`(Table.RecId != 0)`): the standard examples return a RecId or an expression. If a plain list is not accepted, return an expression built from the list.
3. Enum range values written by name (`Open`, `Transferring`, `New`, `Yes`, `No`) and the string statuses of the posting queue.
4. The menu item `Query` replacing the list form's own query: the root data source name of each query equals the form's data source (checked: it does).
5. Tile count equals list rows for an Administrator, a user with stores and a user with no worker link.
6. Field names in `isAllStores` and `warehouses` (`SecurityUserRole`, `SecurityRole.AotName`, `DirPersonUser`, `HcmWorker.Person`).

## Import
Only when the main session says the VM is free. Use a stage folder of your own (`C:\Temp\w1stage`), never `C:\Temp\ps`, and `Set-Location C:\` before handing the VM back. Hash-check, compile first, sync the security, and read the compile log. If it fails, roll back (delete the new files, restore the three shared ones) and recompile before handing back. Do not run `iisreset` or restart services unless the compile shows Errors: 0.

## Status (2026-11-08): imported and working on the dev VM
Imported, compiled (Errors: 0) and checked as Administrator: the tiles show counts. Fixes found on the way, all in the staged files:
- Tile section containers need `ArrangeMethod` HorizontalLeft (pattern Section Tiles).
- The system administrator role's name is `-SYSADMIN-`, not `SystemAdministrator`.
- A function used as a query range value must carry `[QueryRangeFunction]`. Without it every tile fails with "Query extended range failure". An administrator gets `*` (and `!0` for the users tiles), not an expression.
- Warehouse filter on the page: `WorkspacePageFilterGroup` with a lookup (`FilterStringControl`). The choice is kept per user in the AOS cache by `HSMobileOpsWorkspaceFilter` (lost when the AOS restarts) and read by `HSMobileOpsWorkspaceScope::stores()`. Empty means every store the user may see; for a non-administrator the choice narrows their own stores. The form implements `SysIFilterProvider` with an empty filter and the count tiles have ApplyFilter Yes, so they refresh when the box changes. With ADF picked "Postings failed" shows 1 (the database holds 1 for ADF; 18276 failed posts have an empty warehouse).
- Still open: tile counts for a user with stores and for a user with no worker link; the control center list is not scoped.

## Removed 12 Nov 2026
The Cycle stopped tile (tile, query, menu item, its WorkspaceView entry and `cycleBefore`) went with cycle counting; removed from the dev VM too.
