package com.tourism.iam.service;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import java.util.List;
import java.util.Map;



@Slf4j
@Service    
@RequiredArgsConstructor
public class KeycloakAdminService {
    private final Keycloak keycloak;

    @Value("${keycloak.realm}")
    private String realm;
    

    /*
        Connect to a specific realm in Keycloack 
        A realm is the gateway to managing everything within that realm 
        (users, roles, clients, etc.).
    */
    private RealmResource getRealmResource() {
        return keycloak.realm(realm);
    }


    /*
    From the realm, we can then retrieve the specific user management section:
    UsersResource allows: creating users, searching, deleting, updating, resetting passwords...

    */
    private UsersResource getUsersResource() {
        return getRealmResource().users();
    }

    private UsersResource getUserResource() {
        return getRealmResource().users();
    }

    public void logoutUser(String keycloakId) {
        getUsersResource().get(keycloakId).logout();
        log.info("Logged out all sessions for Keycloak user: keycloakId={}", keycloakId);
    }

    public void sendVerificationEmail(String keycloakId) {
        getUsersResource().get(keycloakId).sendVerifyEmail();
        log.info("Sent verification email for Keycloak user: keycloakId={}", keycloakId);
    }

    public String createUser(String email, String password, String fullName, String role, Integer userId){
        //Create user reprensentation (without credentials initially)
        UserRepresentation user = new UserRepresentation();
        user.setUsername(email);  // Keycloak use email for username
        user.setEmail(email);
        user.setFirstName(fullName);
        user.setEnabled(true);  // Enable user immediately so they can login
        user.setEmailVerified(true);  // Mark email as verified to allow login (email verification handled separately)

        //Keycloak manages users using its own UUID (in the format "550e8400
        // When other microservices receive JWT tokens, they need to
        //  know the userId in your database, not Keycloak's UUID.-e29b-41d4-a716-446655440000"), while your iam_db database has its own integer ID (in the format 1, 2, 3...).
        // Solution — save to attribute:

        user.setAttributes(Map.of("userId", List.of(String.valueOf(userId))));
        user.setRequiredActions(List.of());  // No required actions

        //Call Keycloak Admin APT to create user
        Response response = getUsersResource().create(user);
        int status = response.getStatus();

        if(status != 201){
            log.error("Failed to create keyclock user: status={}", status);
            throw new RuntimeException("Cannot create user in keycloak, status: " + status);
        }

        // Get the Keycloak UUID from the returned Location header.
        String location = response.getLocation().toString(); // e.g., http://localhost:8080/auth/admin/realms/tourism/users/550e8400-e29b-41d4-a716-446655440000
        String keycloakId = location.substring(location.lastIndexOf("/") + 1);
        log.info("Created Keycloak user: email={}, keycloakId={}", email, keycloakId);

        //Set password using resetPassword (sets password permanently)
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setTemporary(false);
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        getUserResource().get(keycloakId).resetPassword(credential);
        log.info("Set password for Keycloak user: keycloakId={}", keycloakId);

        //Assign role to user
        assignRole(keycloakId, role);

        return keycloakId;
    }

    /**
     * Enale user after successfully verify email
     */
    public void enableUser(String keycloakId){
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(true);
        user.setEmailVerified(true);
        getUserResource().get(keycloakId).update(user);
        log.info("Enabled Keycloack user: keycloakId={}", keycloakId);
    }

    public void updatePassword(String keycloakId, String newPassword){
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setTemporary(false);
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(newPassword);
        getUserResource().get(keycloakId).resetPassword(credential);
        log.info("Updated password for Keycloack user: keycloakId={}", keycloakId);
    }

     /**
     * Assign realm role to user (CUSTOMER / ADMIN / TOUR_OWNER)
     */
    public void assignRole(String keycloakId, String roleName){
        RoleRepresentation role = getRealmResource().roles().get(roleName).toRepresentation();
        /**
         * realmLevel()? Keycloak has two types of roles: realm-level and client-level. 
         * Realm-level roles are global and can be assigned to any user in the realm, 
         * while client-level roles are specific to a particular client (application). 
         * In this case, we are assigning a realm-level role, which is why we use realmLevel() before add()
         * Realm "tourism"  
         *  ├── Client "tourism-web"     
         *  ├── Client "tourism-mobile"  
         *  └── Client "tourism-admin"  
         */
        getUserResource().get(keycloakId).roles().realmLevel().add(List.of(role));
        log.info("Assigned role '{}' to Keycloack user: keycloakId={}", roleName, keycloakId);
    }



    public String findUserIdByEmail(String email){
        List<UserRepresentation> users = getUserResource().searchByEmail(email, true);
        if(users.isEmpty()){
           return null;
        }
        
        return users.get(0).getId();
    }

    public void disableUser(String keycloakId){
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(false);
        getUserResource().get(keycloakId).update(user);
        log.info("Disabled Keycloack user: keycloakId={}", keycloakId);
    }
}
