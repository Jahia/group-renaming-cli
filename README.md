
# Jahia Group Renaming CLI Command

This module provides an OSGi/Karaf CLI command to rename a Jahia group, migrating all group members and ACLs to the new group name.

## Command

```
jahia:group-rename --from <originalGroupName> --to <destinationGroupName> [--site <siteKey>]
```

### Options

- `--from` (required): The original group name to rename.
- `--to` (required): The new group name.
- `--site` (optional): The site key where the group exists. If omitted, the site is expected to be at the server level.

## What It Does

- Creates a new group with the destination name.
- Copies all members from the original group to the new group.
- Moves all ACLs (granted and denied roles) from the original group to the new group.
- Revokes all permissions from the original group.
- Deletes the original group.

## Example

```
jahia:group-rename --from editors --to senior-editors --site digitall
```

This will rename the `editors` group to `senior-editors` in the `digitall` site, migrating all members and permissions.

```shell
jahia:group-rename --from editors --to senior-editors
```
This will rename the `editors` group to `senior-editors` defined at the server level, migrating all members and permissions.

## Requirements

- Jahia 8+
- Karaf shell access
- Appropriate permissions to manage groups and ACLs

## How to deploy
Build the project using Maven:

```sh
mvn clean install
```
Deploy the built bundle to your Jahia instance via the module management UI.

### Deploy on Docker
Deploy the built bundle to your Jahia instance with Docker:

```sh
mvn clean install jahia:deploy -Djahia.deploy.targetContainerName="jahia"
```

## License

See [LICENSE](./LICENSE) for details.
