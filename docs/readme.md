# Hakusan

An independent Android manga reader. Hakusan is in early development.

## Repositories

Hakusan consists of three repositories:

- [hakusan](https://github.com/arexyl/hakusan): the Android application, written in Kotlin
- [san](https://github.com/arexyl/san): the native image engine, written in Zig
- [hbinds](https://github.com/arexyl/hbinds): the native ABI between them, checked out as a submodule at `share/bindings/`

Clone with submodules:

```sh
git clone --recurse-submodules https://github.com/arexyl/hakusan.git
```

## License

Hakusan is licensed under the GNU Affero General Public License, version 3 or any later version (`AGPL-3.0-or-later`). See [license](../license).
