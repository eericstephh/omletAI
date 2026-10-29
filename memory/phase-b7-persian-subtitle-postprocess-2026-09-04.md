
## Update 2026-09-04 (continued after B7): PHASE B1.7 Large-v3 resilience tests

Added `WhisperLargeV3DownloadResilienceTest` (13 JVM tests, 0 failures) targeting the
`UnifiedModelInstaller` HTTP/install pipeline with a Large-v3-shaped
`ModelRegistry.Spec` (id, dirName, fileName, url, language, minRamMb, whisperThreads).
The test seam is `UnifiedModelInstaller.HttpFetcher` (a clean interface), not the
`AiModelInstaller` production entry point (which has no HTTP seam).

13 tests cover:
1. 5xx → retry → success (consumeCount=2)
2. 5xx → exhaust retries (NETWORK_TIMEOUT, consumeCount=3)
3. 4xx → fail fast (MODEL_DOWNLOAD_FAILED, consumeCount=1)
4. Oversize body (2× declared) → non-retryable abort
5. Empty body → non-retryable
6. Idempotent install (second call is Cached, 0 HTTP)
7. SHA mismatch → partial cleaned, Failed at VERIFYING
8. Mid-stream IOException → partial cleaned after 3 retries
9. Progress fraction monotonicity at 3.1 GB declaredSize
10. Storage pre-flight short-circuit (consumeCount=0)
11. Coroutine cancellation → partial cleaned, CancellationException
12. Lockfile parse round-trip preserves every Large-v3 field bit-for-bit
13. 5xx-200-5xx: installer short-circuits after attempt 2 success

Test seam uses `LvcScriptedHttpFetcher` (named to avoid collision with the
`private class ScriptedHttpFetcher` in `UnifiedModelInstallerTest.kt`).
Two custom `InputStream` fakes: `ExplodingInputStream` (throws IOException
after N bytes) and `BlockingInputStream` (gates on CountDownLatch for
cancellation test).

Full JVM suite: **600 tests, 0 failures, 0 errors** (587 from B1–B7 + 13 new).
