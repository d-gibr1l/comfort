# Static analysis: VidMate 6.0302 and Snaptube 7.63.0.76330810

Date: 2026-10-09. Static analysis only, via rea-wsl (JADX 0.7.1). Nothing was installed, run or runtime-captured, and the original APKs were not modified.
Purpose: personal research for a gallery-dl-based Android media downloader.

Files (WSL Ubuntu):
- `/home/gibril/apk-research/vidmate/vidmate.apk` (sha256 7d076472e2e70cb0eaab3d6c96cadb3dc8811eced1a2b6f575f898781c3f9061)
- `/home/gibril/apk-research/snaptube/snaptube.apk`

## Caveats
- VidMate is mostly obfuscated (class names like `aaab`). Findings come from class names, strings and assets, not decompiled method bodies. Obfuscated logic is not guessed at.
- Signatures were not verified by the tool.
- The VidMate host `package` is `com.video.fun.app`, not `com.nemo.vidmate`. Code and assets use the VidMate name.
- Deviation: VidMate's real app is an XOR-encrypted Tencent Shadow plugin (`assets/4672692053`). The first bytes `e7 fc b4 b3` are `PK\x03\x04` XOR `0xB7`. I decoded a COPY with single-byte XOR 0xB7 to `/home/gibril/apk-research/vidmate_plugin_decoded/plugin.apk` (valid zip, sha256 24f6f998e6d987634f92ffc878744946d6cef6d4f46788c7b01298c1bc2f5534). How `encryptParams "1207"` maps to 0xB7 was not determined.
- Leftover extracted files in WSL: `/tmp/rea-extracted-f918587a-…`, `snaptube/x`, `snaptube/dex`.

## VidMate

1. Identity: `com.video.fun.app`, 6.0302 (60302), minSdk 21, targetSdk 35, 2,499 host classes. The plugin has 21,361 classes.
   Permissions: storage (incl. MANAGE_EXTERNAL_STORAGE), REQUEST_INSTALL_PACKAGES, SYSTEM_ALERT_WINDOW, GET_TASKS, KILL_BACKGROUND_PROCESSES, boot receiver, launcher shortcut and settings permissions, foreground services (data sync / media playback / special use), AD_ID, ad-services attribution, C2DM, INTERNET, network and WiFi state, WAKE_LOCK, POST_NOTIFICATIONS, VIBRATE.
2. Media discovery:
   - Shadow shell (`ShadowWorker`, `shadow.service.*`) plus plugin listed in `assets/config.json` (partKey vidmate/remote/core, encryptType xor).
   - WebView with injected JS: `PageBrowserJS`, the string `javascript:vid_mate_get_video_info();`, `JsBridge.recordFetch/recordFormSubmission/markSwUrl`, `player.getVideoUrl()`, and a JS service bridge at `https://app-jsengine/jsservices/WebViewJsServiceBridge.js`.
   - Native YouTube: `StrategyYouTubeUrlParser`, `IHotFixYtbParser` (about 40 methods), `window.__ytb__parserPlayer(ytInitialPlayerResponse)`, SABR download code. Mozilla Rhino is bundled.
   - Not determined: per-site targets of the injected JS, URL-to-extractor routing, any obfuscated method logic.
3. Sites / config:
   - `com.nemo.vidmate.model.cofig.*` (about 60 config classes), Firebase Remote Config, `assets/ad_mediation_default_config.json`, `/api/ad/union/sdk/strategies/adn`, `api.test.v-mate.mobi/api/adserver/mediation/get/`.
   - Server-delivered extractor code: `requestYtbHotfix`, `hotfix_latest_download_jar_version`, `getVideoTemplateUrl` ("template url or id or md5"). The plugin ships fallback copies at `assets/ht_resource/ds_10593001.jar` and `app_dex_10000020.dex`. No download URL or signature check found.
   - Confirmed site: YouTube. Torrent classes exist. Other sites appear only as host strings.
4. Download libs: OkHttp, Cronet, media3 (DASH/HLS), FFmpeg (`runFFmpeg`, SABR post-live merge), jlibtorrent, libun7z, libvidmatecodec, jsoup, Rhino. No yt-dlp.
5. Ads/tracking: Pangle/ByteDance (+ApmInsight), Mintegral, AppLovin, Huawei Ads, Bidnex, Vungle, Shark Ads, IAB OM SDK, in-house heflash CMS ads, Firebase (Analytics, Crashlytics, Remote Config, Messaging), Play services measurement. Host manifest also has adxmi, wemob, mobpower, avazu, arrkii, etap, hopemobi keys and a Facebook app ID (code not verified).

## Snaptube

1. Identity: `com.snaptube.premium`, 7.63.0.76330810, minSdk 21, targetSdk 35, 25,555 classes.
   Permissions: storage (incl. MANAGE_EXTERNAL_STORAGE), REQUEST_INSTALL_PACKAGES, REQUEST_DELETE_PACKAGES, SYSTEM_ALERT_WINDOW, PACKAGE_USAGE_STATS, QUERY_ALL_PACKAGES, GET_INSTALLED_APPS, GET_PACKAGE_SIZE, KILL_BACKGROUND_PROCESSES, REORDER_TASKS, foreground services, POST_NOTIFICATIONS, boot receiver, AD_ID, install referrer, C2DM, INTERNET, network and WiFi state, WAKE_LOCK.
2. Media discovery:
   - Per-site Java extractors in `com.snaptube.video.videoextractor`: `InstagramVideoExtractor`, `MobiuspaceVideoExtractor`, plus Facebook/Instagram/TikTok/XVideos/GoogleDrive codecs. Rule models: `SiteSupportRules`, `DetailPageRules(version=…)`, `UrlPattern`, `ExtractRuleException`.
   - `com.snaptube.extractor.pluginlib` (73 classes): Youtube, Facebook, `movietv.MovieTvConfig/OnlineSiteRule`, `SiteInjectCode`, YouTube ClientConfig (MWEB/TV_API/IOS_API/DESKTOP), signature deobfuscation, PoToken WebView, SABR data source, Cronet executor.
   - WebView JS injection: `assets/js/loader.js` (webpack bundle, `window.app`, `window.snaptube`), `app.extractWithCurrentContext`, `html5player.sUrlHigh` (XVideos), `BackgroundWebViewExtractManager`, `JavaScriptEnvironment` bridge.
   - Native YouTube InnerTube adapter: `com.snaptube.dataadapter` (about 168 classes), `com.youtube.proto`.
   - Not determined: rule contents, per-site injected-script logic, PluginId enum values.
3. Sites / config:
   - Remote config endpoints: `api.thejeu.com/onlineConfig/`, `online-config.thejeu.com/v2/onlineConfig`, `api.thejeu.com/adapter/v1/`, `/v1/video/bsd2`, `/v2/video/search`, `misc.api.thejeu.com/availability`, `static.snaptube.in/ad-config/hosts.txt`.
   - Plugin update system: `com.snaptube.plugin` (PluginInfoCache, fetchPluginInfo), prefs `lib_plugin_version`, `enable_plugin_download_in_mobile`, `plugin_mobile_download_size_limit`, `should_check_plugin_update_when_error`. What it downloads and from where was not determined.
   - Manifest capability flags (`is_ytb_sabr_support`, `is_ins_multi_format_support`, `is_fb_more_format_support`, `is_m3u8_m4s_support`, `is_movie_tv_extract_support`, and others) look like host/plugin feature negotiation (my reading of the names).
   - `assets/full_sites.json` is a 48-site bookmark list (I read only the first 20 titles). `assets/ad_block_host` has 2,360 ad hosts.
4. Download libs: OkHttp, Cronet, Retrofit, jsoup, Rhino, media3, FFmpeg (`FFmpegHelper`, `FFMpegJni`, `/libffmpeg.so`, the .so is not in the base lib dir), libmp3cvt. yt-dlp appears only in a code comment link. No torrent library.
5. Ads/tracking: InMobi, Mintegral, Pangle/ByteDance, Vungle, MoPub, PubNative, IAB OM SDK, own `com.snaptube.ads` mediation, Sensors Analytics, Firebase (Perf, Dynamic Links, Remote Config, Crashlytics, Messaging), Facebook SDK (manifest metadata only), falconnet applist reporting, Intercom, Zendesk.

## Comparison

VidMate: thin Shadow shell, XOR-encrypted plugin, WebView + injected JS, server-delivered YouTube hotfix dex, heavy obfuscation.
Snaptube: single APK, readable per-site extractors with a rule model, plugin-lib extractor, injected JS, `onlineConfig`, plugin update system.

Worth borrowing for a gallery-dl-based app:
- A versioned extractor/plugin boundary with capability flags (Snaptube).
- A URL-routing/UI rule file separate from code, with a version number (Snaptube `SiteSupportRules`).
- A WebView fetch/XHR hook as a fallback when an extractor fails, with a cookie jar.
- A background (hidden) WebView extraction path.
- Remote extractor updates, with integrity checks (neither app showed any).
- A thin OkHttp/Cronet download layer with FFmpeg merging for DASH/HLS.

Not worth borrowing:
- XOR-encrypted plugin and Shadow loader.
- Remote switches for ads, and the ad/analytics SDK stack.
- Broad permissions (QUERY_ALL_PACKAGES, GET_INSTALLED_APPS, installer, overlay).
- SABR/PoToken reverse engineering (leave YouTube to yt-dlp).
- Obfuscated server-delivered code that can't be audited.
