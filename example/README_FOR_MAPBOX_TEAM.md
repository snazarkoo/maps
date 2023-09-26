# For map mapbox team

1. Clone the fork https://github.com/snazarkoo/maps.git
2. Switch to `feat/offline-manager-legacy` branch
3. Install deps for rnmapbox `cd maps && yarn`
4. Create `accesstoken` file in `maps/example`. Past your mapbox token here
5. Add `MAPBOX_DOWNLOADS_TOKEN` to gradle.properties
6. Start metro bundler `yarn start` 
7. Sync and build project via Android Studio or via CL. For this check README.md
8. Open the app, select `Map -> Offline example -> Create pack`
9. Filter logs by tag `OfflineModuleV10Legacy`. You should see logs for all status changes during the download process;
10. To switch deps to v9, open `example/build.gradle`, replace `RNMapboxMapsImpl = "mapbox"` with `RNMapboxMapsImpl = "mapbox-gl"`.
11. Sync and build project
12. Clear app data
13. Create a pack for V9 `Map -> Offline example -> Create pack`
14. Filter logs by tag by `OfflineModuleV9`