package org.jahia.support.modules.group.renaming.cli;

import org.apache.commons.lang.StringUtils;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.jahia.api.Constants;
import org.jahia.services.content.JCRContentUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRGroupNode;
import org.jahia.services.usermanager.JahiaGroupManagerService;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Command(scope = "jahia", name = "group-rename", description = "Rename a Jahia Group, move all existing acls to the new group name")
@Service
public class RenameJahiaGroup implements Action {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RenameJahiaGroup.class);

    @Reference
    JahiaGroupManagerService jahiaGroupManagerService;

    @Reference
    JCRSessionFactory sessionFactory;

    @Option(name = "--from", description = "Original group name", required = true)
    private String originalGroupName;

    @Option(name = "--to", description = "Destination group name", required = true)
    private String destinationGroupName;

    @Option(name = "--site", description = "Site key to rename group in")
    private String siteKey = null;


    @Override
    public Object execute() throws Exception {
        if (StringUtils.isBlank(originalGroupName)) {
            throw new IllegalArgumentException("Original group name must be provided");
        }
        if (StringUtils.isBlank(destinationGroupName)) {
            throw new IllegalArgumentException("Destination group name must be provided");
        }
        if (!jahiaGroupManagerService.groupExists(siteKey, originalGroupName)) {
            throw new IllegalArgumentException("Original group name does not exist: " + originalGroupName);
        }
        if (jahiaGroupManagerService.groupExists(siteKey, destinationGroupName)) {
            throw new IllegalArgumentException("Destination group name already exists: " + destinationGroupName);
        }
        logger.info("Renaming group " + originalGroupName + " to " + destinationGroupName);
        JCRSessionWrapper session = sessionFactory.getCurrentSystemSession(Constants.EDIT_WORKSPACE, null, null);
        JCRGroupNode originalGroup = jahiaGroupManagerService.lookupGroup(siteKey, originalGroupName, session);
        JCRGroupNode destinationGroup = jahiaGroupManagerService.createGroup(siteKey, destinationGroupName, null, false, session);
        if (destinationGroup == null) {
            throw new IllegalArgumentException("Failed to create destination group: " + destinationGroupName);
        }
        logger.info("Adding members from original group {} to destination group {}", originalGroupName, destinationGroupName);
        destinationGroup.addMembers(originalGroup.getMembers());
        String originalPrincipalKey = "g:" + StringUtils.substringAfterLast(originalGroup.getPath(), "/");
        String destinationPrincipalKey = "g:" + StringUtils.substringAfterLast(destinationGroup.getPath(), "/");
        AclsMaps principalAcl = getPrincipalAcl(originalPrincipalKey, session, siteKey);
        logger.info("Moving ACLs from group " + originalGroupName + " to group " + destinationGroupName);
        moveAclRoles(principalAcl.getMapGranted(), originalPrincipalKey, destinationPrincipalKey, session, true);
        moveAclRoles(principalAcl.getMapDenied(), originalPrincipalKey, destinationPrincipalKey, session, false);
        logger.info("Removing original group {}", originalGroupName);
        jahiaGroupManagerService.deleteGroup(originalGroup.getPath(), session);
        session.save();
        return null;
    }

    private void moveAclRoles(Map<String, Set<String>> aclMap, String originalPrincipalKey, String destinationPrincipalKey, JCRSessionWrapper session, boolean grant) {
        for (Map.Entry<String, Set<String>> entry : aclMap.entrySet()) {
            String path = entry.getKey();
            Set<String> roles = entry.getValue();
            logger.info("{} roles - {} to {}", grant ? "GRANTING" : "DENYING", String.join(", ", roles), destinationPrincipalKey);
            try {
                JCRNodeWrapper node = session.getNode(path);
                if (grant) {
                    node.grantRoles(destinationPrincipalKey, roles);
                } else {
                    node.denyRoles(destinationPrincipalKey, roles);
                }
                node.revokeRolesForPrincipal(originalPrincipalKey);
            } catch (RepositoryException e) {
                logger.error("Failed to update ACL for path {}: {}", path, e.getMessage());
            }
        }
    }


    private AclsMaps getPrincipalAcl(final String principallKey, JCRSessionWrapper session, String siteKey) throws RepositoryException {

        Query query = session.getWorkspace().getQueryManager().createQuery(
                "select * from [jnt:ace] as ace where ace.[j:principal] = '" + JCRContentUtils.sqlEncode(principallKey) + "'",
                Query.JCR_SQL2);
        logger.info("Query: {}", query.getStatement());
        QueryResult queryResult = query.execute();
        NodeIterator rowIterator = queryResult.getNodes();

        Map<String, Set<String>> mapGranted = new LinkedHashMap<>();
        Map<String, Set<String>> mapDenied = new LinkedHashMap<>();

        while (rowIterator.hasNext()) {
            JCRNodeWrapper node = (JCRNodeWrapper) rowIterator.next();
            if (siteKey != null && !node.getResolveSite().getName().equals(siteKey)) {
                continue;
            }
            String path = node.getParent().getParent().getPath();
            Set<String> foundRoles = new HashSet<>();
            boolean granted = "GRANT".equals(node.getProperty("j:aceType").getString());
            Value[] roles = node.getProperty(Constants.J_ROLES).getValues();
            for (Value r : roles) {
                String role = r.getString();
                foundRoles.add(role);
            }
            if ("/".equals(path)) {
                path = "";
            }
            (granted ? mapGranted : mapDenied).put(path, foundRoles);
        }
        logger.info("Found {} granted roles", mapGranted.size());
        logger.info("Found {} denied roles", mapDenied.size());
        return new AclsMaps(mapGranted, mapDenied);
    }

    private static class AclsMaps {
        private final Map<String, Set<String>> mapDenied;
        private final Map<String, Set<String>> mapGranted;

        public AclsMaps(Map<String, Set<String>> mapGranted, Map<String, Set<String>> mapDenied) {
            this.mapGranted = mapGranted;
            this.mapDenied = mapDenied;
        }

        public Map<String, Set<String>> getMapDenied() {
            return mapDenied;
        }

        public Map<String, Set<String>> getMapGranted() {
            return mapGranted;
        }
    }
}
