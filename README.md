# VCF Content Factory — SDK adapter template

A minimal, fully-wired skeleton for a **Tier 2 Java SDK management-pack adapter**
for VCF Operations, built and released independently of the VCF Content Factory.

This template tracks the current framework v2 idioms used by the production
adapters (`compliance`, `unifi`). The example adapter discovers one
`ExampleResource` kind under a `World` traversal anchor and pushes one metric and
one property per resource — replace the resource kinds, endpoints, and metric
keys with your target system, keeping the SHAPE.

## Documentation

Every SDK pak ships a standard docset under [`docs/`](docs/README.md):
a generated inventory-tree diagram (kinds + identifying keys), plus
hand-curated `overview.md` and `installing.md` prose pages. The two prose
pages are scaffolded once by `docs-gen` and then curated — in this template
they carry placeholder guidance telling you what to write when you
instantiate a pak. Regenerate the derived files (diagram, inventory tree,
`docs/README.md`) on every build; never hand-edit them.

## What's here

```
adapter.yaml                              # name, version, build_number, adapter_kind, entry_class
describe.xml                              # adapter descriptor (ResourceKinds, metrics, credentials)
resources/resources.properties            # nameKey -> display strings
icons/icons.yaml                          # ResourceKind -> icon mapping (optional)
src/com/vcfcf/adapters/example/           # adapter Java source
  ExampleAdapter.java                     #   the adapter (heavily commented — read this first)
  ExampleApiClient.java                   #   thin REST client
  ExampleConfig.java                      #   typed config POJO
.github/workflows/build-pak-on-tag.yml    # CI: tag v* -> build .pak -> attach to Release
CHANGELOG.md                              # one line per build
```

There is **no `lib/` of bundled jars** (C2 pak shape — see below).

## Instantiating a new pak

1. **Create the repo from this template.** Click **Use this template** to create
   `sentania-labs/vcf-content-factory-sdk-<name>`.
2. **Register it in the factory.** Add one line to the factory's
   `context/managed_paks.md` registry so `scripts/bootstrap_managed_paks.sh`
   clones it into the gitignored `content/sdk-adapters/<name>/` working tree.
3. **Rename the skeleton.** Pick your `adapter_kind` (snake_case) and apply it
   consistently across:
   - `adapter.yaml` — `adapter_kind`, `entry_class`, `name`, `description`;
   - `describe.xml` — the `AdapterKind` key, the type-7 instance `ResourceKind`
     key, credential/resource kinds, identifiers, groups, attributes;
   - `resources/resources.properties` — the display strings;
   - `icons/icons.yaml` — the resource-kind icon map;
   - the Java package/class names and the `ADAPTER_KIND` constant.
4. **Build out collection.** Replace the example HTTP calls and per-resource
   collect logic. Keep the load-bearing idioms documented at the top of
   `ExampleAdapter.java` — especially **collect-path discovery** (mandatory on
   VCF Ops 9.0.2, which never calls `onDiscover()`), `componentLogger(Class)`,
   cooperative cancellation, and loud-failure (no sentinel for an unreadable
   value).

## Local dev build (preview)

The official `.pak` is built by CI on a tag (next section). For a local preview,
use the factory's builder. The Broadcom SDK jar (`vrops-adapters-sdk-2.2.jar`) is
**not** shipped — supply it from your appliance:

```sh
# Obtain the SDK jar once from a VCF Ops appliance:
#   scp root@<appliance>:/usr/lib/vmware-vcops/common-lib/vrops-adapters-sdk-2.2.jar .

# Either pass it explicitly:
python3 -m vcfops_managementpacks build-sdk content/sdk-adapters/<name> \
  --sdk-jar /path/to/vrops-adapters-sdk-2.2.jar -o dist

# ...or set it once in the environment:
export VCFCF_SDK_JAR=/path/to/vrops-adapters-sdk-2.2.jar
python3 -m vcfops_managementpacks build-sdk content/sdk-adapters/<name> -o dist
```

Validate first (the cheap loop — exhaust this before building a pak):

```sh
python3 -m vcfops_managementpacks validate-sdk content/sdk-adapters/<name>
```

The framework jar (`vcfcf-adapter-base.jar`) is provided by the builder; you do
**not** commit it.

## CI release contract (the official artifact)

This repo is the **single source of truth** for its `.pak`. The shippable artifact
is built by CI, never on a laptop:

1. Author + commit + push to `main`.
2. **Push a `vX.Y.Z` tag.** The `build-pak-on-tag` workflow:
   - pulls the published `sdk-buildkit` tarball from the factory's Releases,
   - fetches the private Broadcom SDK jar from
     `sentania-labs/vcf-content-factory-sdk-runtime` (release `sdk-2.2`),
   - builds the `.pak` deterministically (no agent, no factory checkout),
   - gates on `pak-compare` (zero BLOCKING required), and
   - attaches the `.pak` to the tag's GitHub Release.

That Release asset **is** the release. A factory `/publish` that references this
pak emits only a **pointer** to the latest Release — it never rebuilds or mirrors
the binary.

### Required org/repo secret

`SDK_RUNTIME_TOKEN` — a fine-grained, read-only PAT scoped to
`sentania-labs/vcf-content-factory-sdk-runtime` with **Contents: read**. The
Broadcom SDK jar is stored as a private release asset there and is never bundled
in this repo or the buildkit (C2 redistribution constraint). Add the secret as an
Actions secret (repo or org level) **before** pushing a release tag. See the
workflow header for the runner/JDK/buildkit knobs.

## C2 pak shape — no bundled jars

This pak **never** carries `vrops-adapters-sdk` or any Broadcom jar:

- `vcfcf-adapter-base.jar` (the VCF-CF framework) is provided by the buildkit and
  copied into the pak's `lib/` at build time.
- `vrops-adapters-sdk-*.jar` is on the appliance's shared classpath at runtime and
  is supplied to the *compiler* by the consumer (`--sdk-jar` / `VCFCF_SDK_JAR`);
  it is never placed in the pak.

`.gitignore` ignores `lib/*.jar` so a stray jar can't be committed.

## Bundled content (optional)

For an adapter that ships dashboards/views, add a `bundled_content:` block to
`adapter.yaml` and co-locate the YAML **in this repo** under `views/` and
`dashboards/` (paths resolve relative to `adapter.yaml`).
