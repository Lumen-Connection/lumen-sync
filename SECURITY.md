# Security

Please report a suspected vulnerability privately through the repository host's security-advisory feature. Do not open a public issue containing exploit details or private device identifiers.

Useful reports include affected Lumen Sync and Syncthing versions, operating system, reproduction steps, and whether the problem is reachable remotely or requires local access. Remove folder names, file contents, API keys, and full device IDs from logs before sharing them.

The project currently has no hosted control plane. Its most security-sensitive boundaries are invite approval, the loopback REST API, native core provenance, filesystem permissions, and process lifecycle.
