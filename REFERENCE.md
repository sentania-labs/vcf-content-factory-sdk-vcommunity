# VCF Content Factory Example Adapter — Reference

Generated from `describe.xml` and `resources.properties` for build 0.1.0.1.

## Adapter

| Field | Value |
|---|---|
| Adapter Kind | `example_adapter` |
| Tier | 2 (Java SDK) |
| Monitoring Interval | 5 minutes |
| License Required | No |

### Credentials

| Field | Key | Type |
|---|---|---|
| Username | `username` | string |
| Password | `password` | string (masked) |

### Connection Settings

| Field | Key | Default | Required |
|---|---|---|---|
| Host / IP Address | `host` | — | Yes |
| Port (HTTPS) | `port` | 443 | No |
| Allow Insecure SSL | `allowInsecure` | true | No |

---

## Object Types

### Example World

**Identifier**: `world_id` (World ID)

---

### Example Resource

**Identifier**: `id` (Resource ID)

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `value` | Value | metric | — | yes |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `name` | Name | property | — | — |

---

## Traversal Spec

**Name**: Example Infrastructure

```
example_adapter
    └── Example World
        └── Example Resource
```
