package org.tanzu.goosechat;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class AuthController {

    @GetMapping("/auth/status")
    public ResponseEntity<Map<String, Object>> authStatus(Authentication authentication) {
        boolean isAuthenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        String userId = "";
        String username = "";
        String email = "";
        String displayName = "";

        if (isAuthenticated && authentication.getPrincipal() instanceof OAuth2User oAuth2User) {
            // sub claim from UAA provides a stable, unique user identity across SSO-bound apps
            if (oAuth2User instanceof OidcUser oidcUser) {
                userId = oidcUser.getSubject();
            }
            if (userId == null || userId.isEmpty()) {
                Object sub = oAuth2User.getAttributes().get("sub");
                userId = sub != null ? String.valueOf(sub) : "";
            }

            username = oAuth2User.getName();
            Object emailAttr = oAuth2User.getAttributes().get("email");
            Object nameAttr = oAuth2User.getAttributes().get("name");
            Object loginAttr = oAuth2User.getAttributes().get("login");

            email = emailAttr != null ? String.valueOf(emailAttr) : "";

            if (nameAttr != null) {
                displayName = String.valueOf(nameAttr);
            } else if (loginAttr != null) {
                displayName = String.valueOf(loginAttr);
            } else if (!username.isEmpty()) {
                displayName = username;
            }
            if (displayName.isEmpty()) {
                displayName = email;
            }
        }

        return ResponseEntity.ok(Map.of(
                "authenticated", isAuthenticated,
                "userId", userId,
                "username", username,
                "email", email,
                "displayName", displayName
        ));
    }
}
