# Changelog

## 0.1.1

- A repository with releases is no longer reported as having none ("No release is published there"): through the
  signed-in GitHub account droidtop answers with the body as text, which was read as empty. Both forms are read,
  and the request now asks for base64 as the other requests do.

## 0.1.0

- First version: release pages on GitHub, GitLab, Codeberg and other Forgejo/Gitea/GitLab servers as an app
  catalog for droidtop (apps.catalog@1). Add by link, address or Obtainium export; the APK is chosen by ABI and
  name pattern, pre-releases are optional per app, the app's package, version code and signing key are read
  from the APK ahead of the download and the key is pinned when you accept; the signed-in GitHub account is
  used for requests where droidtop offers it.
