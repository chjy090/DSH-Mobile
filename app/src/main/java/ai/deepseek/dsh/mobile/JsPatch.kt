package ai.deepseek.dsh.mobile

/**
 * document-start 注入脚本。
 *
 * 原则：脚本必须自包含、幂等、任何异常都不能影响宿主页面。
 */
object JsPatch {

    /**
     * 剪贴板补丁：
     *
     * - `navigator.clipboard` 不存在（http 等非安全上下文）：沿用原生桥 polyfill；
     * - `navigator.clipboard.writeText` 存在（https 安全上下文）：**包装而不是跳过**，
     *   优先调用浏览器原生实现，失败（权限拒绝、页面策略限制等）再回退到
     *   `window.DSHNative.copyToClipboard`，保证原生桥兜底始终有效。
     *
     * 重复注入时由 `window.__dshClipboardPatched` 短路，不会重复包装。
     * `readText` 等其它 API 保持原样。
     */
    fun clipboardPolyfillScript(): String = """
        (function () {
          try {
            if (typeof window === 'undefined' || typeof navigator === 'undefined') return;
            if (window.__dshClipboardPatched) return;
            window.__dshClipboardPatched = true;

            function nativeCopy(text) {
              try {
                var bridge = window.DSHNative;
                if (!bridge || typeof bridge.copyToClipboard !== 'function') {
                  return Promise.reject(new Error('native clipboard unavailable'));
                }
                bridge.copyToClipboard(String(text == null ? '' : text));
                return Promise.resolve();
              } catch (e) {
                return Promise.reject(e);
              }
            }

            var existing = null;
            try { existing = navigator.clipboard; } catch (e) { existing = null; }

            if (existing && typeof existing.writeText === 'function') {
              try {
                var orig = existing.writeText;
                var wrapped = function (text) {
                  return Promise.resolve()
                    .then(function () {
                      return orig.call(existing, String(text == null ? '' : text));
                    })
                    .catch(function () {
                      return nativeCopy(text);
                    });
                };
                try {
                  existing.writeText = wrapped;
                } catch (e) {
                  Object.defineProperty(existing, 'writeText', {
                    value: wrapped,
                    configurable: true,
                    writable: true
                  });
                }
              } catch (e) { }
              return;
            }

            var clipboard = existing || {};
            var writeText = function (text) {
              return nativeCopy(text);
            };
            try {
              if (typeof clipboard.writeText !== 'function') clipboard.writeText = writeText;
            } catch (e) { }

            if (!existing) {
              var readText = function () {
                return Promise.reject(new Error('unsupported'));
              };
              try {
                if (typeof clipboard.readText !== 'function') clipboard.readText = readText;
              } catch (e) { }
              try {
                Object.defineProperty(navigator, 'clipboard', {
                  value: clipboard,
                  configurable: true,
                  writable: true
                });
              } catch (e) {
                try { navigator.clipboard = clipboard; } catch (e2) { }
              }
            }
          } catch (e) { }
        })();
    """.trimIndent()

    /**
     * getUserMedia 兜底（v0.2.2）：
     *
     * Android WebView 在请求了 `echoCancellation` / `noiseSuppression` 的
     * getUserMedia 时会选择“通信设备”。App 清单缺少 MODIFY_AUDIO_SETTINGS 时，
     * Chromium 报 `Unable to select communication device!`，JS 侧得到
     * `NotReadableError: Could not start audio source`。
     *
     * 主修复是清单补权限；此脚本作为第二道保险：先按页面原始 constraints 调用，
     * 失败且错误为 NotReadableError / OverconstrainedError 时，放宽为
     * `audio: true`（保留 `deviceId.exact`）+ `video: false` 重试一次；仍失败则
     * 通过 `window.DSHNative.log` 上报 name/message/constraints（截断），并原样
     * 抛出第一次的错误，不改变页面行为。
     *
     * 只对声明了 audio 的调用做重试（视频等非音频调用行为不变）；幂等
     * （`window.__dshGumPatched`）；所有异常都被隔离，注入失败不影响页面。
     */
    fun getUserMediaPatchScript(): String = """
        (function () {
          try {
            if (typeof window === 'undefined' || typeof navigator === 'undefined') return;
            if (window.__dshGumPatched) return;

            var mediaDevices = null;
            try { mediaDevices = navigator.mediaDevices; } catch (e) { mediaDevices = null; }
            if (!mediaDevices || typeof mediaDevices.getUserMedia !== 'function') return;

            var orig = mediaDevices.getUserMedia;

            function invoke(constraints) {
              try {
                return Promise.resolve(orig.call(mediaDevices, constraints));
              } catch (e) {
                return Promise.reject(e);
              }
            }

            function errorName(error) {
              try { return error && error.name ? String(error.name) : ''; } catch (e) { return ''; }
            }

            function errorMessage(error) {
              try { return error && error.message ? String(error.message) : ''; } catch (e) { return ''; }
            }

            function isRelaxable(error) {
              var name = errorName(error);
              return name === 'NotReadableError' || name === 'OverconstrainedError';
            }

            function wantsAudio(constraints) {
              try {
                if (!constraints || typeof constraints !== 'object') return false;
                if (typeof constraints.audio === 'undefined') return false;
                return constraints.audio !== false && constraints.audio !== null;
              } catch (e) {
                return false;
              }
            }

            function exactDeviceId(constraints) {
              try {
                var audio = constraints && constraints.audio;
                if (!audio || typeof audio !== 'object') return '';
                var deviceId = audio.deviceId;
                if (!deviceId || typeof deviceId !== 'object') return '';
                return typeof deviceId.exact === 'string' ? deviceId.exact : '';
              } catch (e) {
                return '';
              }
            }

            function relaxedConstraints(constraints) {
              var id = exactDeviceId(constraints);
              var audio = id ? { deviceId: { exact: id } } : true;
              return { audio: audio, video: false };
            }

            function describe(constraints) {
              try {
                var text = JSON.stringify(constraints);
                if (typeof text !== 'string') return String(constraints);
                return text.length > 300 ? text.slice(0, 300) + '...' : text;
              } catch (e) {
                return '[unserializable]';
              }
            }

            function report(firstError, secondError, constraints, relaxed) {
              try {
                var bridge = window.DSHNative;
                if (!bridge || typeof bridge.log !== 'function') return;
                bridge.log(
                  'getUserMedia failed: ' + errorName(firstError) + ': ' + errorMessage(firstError) +
                  ' | relaxed retry failed: ' + errorName(secondError) + ': ' + errorMessage(secondError) +
                  ' | constraints=' + describe(constraints) +
                  ' | relaxed=' + describe(relaxed)
                );
              } catch (e) { }
            }

            var wrapped = function (constraints) {
              return invoke(constraints).then(
                function (stream) { return stream; },
                function (firstError) {
                  if (!wantsAudio(constraints) || !isRelaxable(firstError)) {
                    return Promise.reject(firstError);
                  }
                  var relaxed = relaxedConstraints(constraints);
                  return invoke(relaxed).then(
                    function (stream) { return stream; },
                    function (secondError) {
                      report(firstError, secondError, constraints, relaxed);
                      return Promise.reject(firstError);
                    }
                  );
                }
              );
            };

            try {
              mediaDevices.getUserMedia = wrapped;
            } catch (e) {
              try {
                Object.defineProperty(mediaDevices, 'getUserMedia', {
                  value: wrapped,
                  configurable: true,
                  writable: true
                });
              } catch (e2) {
                return;
              }
            }
            window.__dshGumPatched = true;
          } catch (e) { }
        })();
    """.trimIndent()

    /**
     * 极少量安全样式，不猜测 dsh 的 class 名。由 WebHost 作为 `<style>` 注入。
     */
    fun baseCss(): String = "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}"
}
