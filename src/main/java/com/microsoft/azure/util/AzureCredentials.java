/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE file in the project root for license information.
 */
package com.microsoft.azure.util;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.resources.Subscription;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collections;
import java.util.HashMap;

public class AzureCredentials extends BaseStandardCredentials {
    public static class ValidationException extends Exception {

        public ValidationException(String message) {
            super(message);
        }
    }

    public static class Constants {
        static final String ENV_AZURE = "Azure";
        static final String ENV_AZURE_CHINA = "Azure China";
        static final String ENV_AZURE_GERMANY = "Azure Germany";
        static final String ENV_AZURE_US_GOVERNMENT = "Azure US Government";
    }

    public static class ServicePrincipal implements java.io.Serializable {
        private static final long serialVersionUID = 1L;

        private final Secret subscriptionId;
        private final Secret clientId;
        private final Secret clientSecret;
        private Secret oauth2TokenEndpoint; //keeping this for backwards compatibility
        private String serviceManagementURL;
        private Secret tenant;
        private String authenticationEndpoint;
        private String resourceManagerEndpoint;
        private String graphEndpoint;

        private String azureEnvironmentName;

        private transient AzureEnvironment azureEnvironment;

        public String getSubscriptionId() {
            if (subscriptionId == null) {
                return "";
            } else {
                return subscriptionId.getPlainText();
            }
        }

        public String getClientId() {
            if (clientId == null) {
                return "";
            } else {
                return clientId.getPlainText();
            }
        }

        public String getClientSecret() {
            if (clientSecret == null) {
                return "";
            } else {
                return clientSecret.getPlainText();
            }
        }

        public String getTenant() {
            if (tenant == null || StringUtils.isBlank(tenant.getPlainText())) {
                if (oauth2TokenEndpoint != null) {
                    return ServicePrincipal.getTenantFromTokenEndpoint(
                            oauth2TokenEndpoint.getPlainText());
                } else {
                    return ServicePrincipal.getTenantFromTokenEndpoint("");
                }
            } else {
                return tenant.getPlainText();
            }
        }

        String getAzureEnvironmentName() {
            return azureEnvironmentName;
        }

        public AzureEnvironment getAzureEnvironment() {
            if (azureEnvironment != null) {
                return azureEnvironment;
            }

            AzureEnvironment baseEnvironment = null;
            String envName = getAzureEnvironmentName();
            if (Constants.ENV_AZURE.equalsIgnoreCase(envName)) {
                baseEnvironment = AzureEnvironment.AZURE;
            } else if (Constants.ENV_AZURE_CHINA.equalsIgnoreCase(envName)) {
                baseEnvironment = AzureEnvironment.AZURE_CHINA;
            } else if (Constants.ENV_AZURE_GERMANY.equalsIgnoreCase(envName)) {
                baseEnvironment = AzureEnvironment.AZURE_GERMANY;
            } else if (Constants.ENV_AZURE_US_GOVERNMENT.equalsIgnoreCase(envName)) {
                baseEnvironment = AzureEnvironment.AZURE_US_GOVERNMENT;
            } else {
                // If the environment name is not recognized, which may happen when the user upgraded the plugin
                // and didn't update the credentials, we try to match a known environment.
                for (AzureEnvironment env : AzureEnvironment.knownEnvironments()) {
                    if (matchEnvironment(env)) {
                        baseEnvironment = env;
                        break;
                    }
                }
                if (baseEnvironment == null) {
                    // fallback using the default Azure environment
                    baseEnvironment = AzureEnvironment.AZURE;
                }
            }

            // The AzureEnvironment#endpoints() method is exposing the internal endpoint map, which means the call site
            // may change the details of the built-in known environments.
            // The ideal fix should be applied in Azure SDK. Here we make a copy so that other plugins that calls this
            // method won't modify the known environments by accident.
            azureEnvironment = new AzureEnvironment(new HashMap<>(baseEnvironment.endpoints()));

            resolveOverride(azureEnvironment, AzureEnvironment.Endpoint.MANAGEMENT, serviceManagementURL);
            resolveOverride(azureEnvironment, AzureEnvironment.Endpoint.ACTIVE_DIRECTORY, authenticationEndpoint);
            resolveOverride(azureEnvironment, AzureEnvironment.Endpoint.RESOURCE_MANAGER, resourceManagerEndpoint);
            resolveOverride(azureEnvironment, AzureEnvironment.Endpoint.GRAPH, graphEndpoint);

            return azureEnvironment;
        }

        /**
         * @deprecated Use {@link #getManagementEndpoint()}
         */
        @Deprecated
        public String getServiceManagementURL() {
            return getManagementEndpoint();
        }

        public String getManagementEndpoint() {
            AzureEnvironment env = getAzureEnvironment();
            return env.managementEndpoint();
        }

        /**
         * @deprecated Use {@link #getActiveDirectoryEndpoint()}.
         */
        @Deprecated
        public String getAuthenticationEndpoint() {
            return getActiveDirectoryEndpoint();
        }

        public String getActiveDirectoryEndpoint() {
            AzureEnvironment env = getAzureEnvironment();
            return env.activeDirectoryEndpoint();
        }

        public String getResourceManagerEndpoint() {
            AzureEnvironment env = getAzureEnvironment();
            return env.resourceManagerEndpoint();
        }

        public String getGraphEndpoint() {
            AzureEnvironment env = getAzureEnvironment();
            return env.graphEndpoint();
        }

        /**
         * For backward compatibility.
         *
         * @deprecated use tenant related methods instead
         */
        @Deprecated
        void setOauth2TokenEndpoint(String oauth2TokenEndpoint) {
            this.oauth2TokenEndpoint = null;
            if (StringUtils.isNotBlank(oauth2TokenEndpoint)) {
                this.tenant = Secret.fromString(ServicePrincipal.getTenantFromTokenEndpoint(oauth2TokenEndpoint));
            }
        }

        void setTenant(String tenant) {
            this.tenant = Secret.fromString(tenant);
            if (StringUtils.isNotBlank(this.tenant.getPlainText())) {
                this.oauth2TokenEndpoint = null;
            }
        }

        void setServiceManagementURL(String serviceManagementURL) {
            this.serviceManagementURL = StringUtils.trimToNull(serviceManagementURL);
            this.azureEnvironment = null;
        }

        void setAuthenticationEndpoint(String authenticationEndpoint) {
            this.authenticationEndpoint = StringUtils.trimToNull(authenticationEndpoint);
            this.azureEnvironment = null;
        }

        void setResourceManagerEndpoint(String resourceManagerEndpoint) {
            this.resourceManagerEndpoint = StringUtils.trimToNull(resourceManagerEndpoint);
            this.azureEnvironment = null;
        }

        void setGraphEndpoint(String graphEndpoint) {
            this.graphEndpoint = StringUtils.trimToNull(graphEndpoint);
            this.azureEnvironment = null;
        }

        void setAzureEnvironmentName(String azureEnvironmentName) {
            this.azureEnvironmentName = azureEnvironmentName;
            this.azureEnvironment = null;
        }

        private boolean matchEnvironment(AzureEnvironment env) {
            return !isOverridden(env.managementEndpoint(), serviceManagementURL)
                    && !isOverridden(env.resourceManagerEndpoint(), resourceManagerEndpoint)
                    && !isOverridden(env.activeDirectoryEndpoint(), authenticationEndpoint)
                    && !isOverridden(env.graphEndpoint(), graphEndpoint);
        }

        private boolean resolveOverride(
                AzureEnvironment environment, AzureEnvironment.Endpoint endpoint, String stored) {
            if (StringUtils.isBlank(stored)) {
                return false;
            }
            String defaultValue = environment.endpoints().get(endpoint.identifier());
            if (StringUtils.isBlank(defaultValue)) {
                // should not happen
                environment.endpoints().put(endpoint.identifier(), stored);
                return true;
            }
            if (isOverridden(defaultValue, stored)) {
                environment.endpoints().put(endpoint.identifier(), stored);
                return true;
            }
            return false;
        }

        private boolean isOverridden(String defaultURL, String overrideURL) {
            return StringUtils.isNotBlank(overrideURL)
                    && !defaultURL.replaceAll("/+$", "").equals(overrideURL.replaceAll("/+$", ""));
        }

        public ServicePrincipal(
                String subscriptionId,
                String clientId,
                String clientSecret) {
            this.subscriptionId = Secret.fromString(subscriptionId);
            this.clientId = Secret.fromString(clientId);
            this.clientSecret = Secret.fromString(clientSecret);
            this.tenant = Secret.fromString("");
        }

        public ServicePrincipal() {
            this.subscriptionId = Secret.fromString("");
            this.clientId = Secret.fromString("");
            this.clientSecret = Secret.fromString("");
            this.tenant = Secret.fromString("");
        }

        public boolean isBlank() {
            return StringUtils.isBlank(subscriptionId.getPlainText())
                    || StringUtils.isBlank(clientId.getPlainText())
                    || StringUtils.isBlank(getTenant())
                    || StringUtils.isBlank(clientSecret.getPlainText());
        }

        public boolean validate() throws ValidationException {
            if (StringUtils.isBlank(subscriptionId.getPlainText())) {
                throw new ValidationException(Messages.Azure_SubscriptionID_Missing());
            }
            if (StringUtils.isBlank(clientId.getPlainText())) {
                throw new ValidationException(Messages.Azure_ClientID_Missing());
            }
            if (StringUtils.isBlank(clientSecret.getPlainText())) {
                throw new ValidationException(Messages.Azure_ClientSecret_Missing());
            }
            if (StringUtils.isBlank(getTenant())) {
                throw new ValidationException(Messages.Azure_OAuthToken_Malformed());
            }

            try {
                final String credentialSubscriptionId = getSubscriptionId();
                Azure.Authenticated auth = Azure.authenticate(
                        new ApplicationTokenCredentials(
                                getClientId(),
                                getTenant(),
                                getClientSecret(),
                                getAzureEnvironment()));
                for (Subscription subscription : auth.subscriptions().list()) {
                    if (subscription.subscriptionId().equalsIgnoreCase(credentialSubscriptionId)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                throw new ValidationException(Messages.Azure_CantValidate());
            }
            throw new ValidationException(Messages.Azure_Invalid_SubscriptionId());
        }

        private static final int TOKEN_ENDPOINT_URL_ENDPOINT_POSTION = 3;

        private static String getTenantFromTokenEndpoint(String oauth2TokenEndpoint) {
            if (!oauth2TokenEndpoint.matches(
                    "https{0,1}://[a-zA-Z0-9\\.]*/[a-z0-9\\-]*/?.*$")) {
                return "";
            } else {
                String[] parts = oauth2TokenEndpoint.split("/");
                if (parts.length < TOKEN_ENDPOINT_URL_ENDPOINT_POSTION + 1) {
                    return "";
                } else {
                    return parts[TOKEN_ENDPOINT_URL_ENDPOINT_POSTION];
                }
            }
        }
    }

    private final ServicePrincipal data;

    @DataBoundConstructor
    public AzureCredentials(
            CredentialsScope scope,
            String id,
            String description,
            String subscriptionId,
            String clientId,
            String clientSecret) {
        super(scope, id, description);
        data = new ServicePrincipal(subscriptionId, clientId, clientSecret);
    }

    public static AzureCredentials.ServicePrincipal getServicePrincipal(
            String credentialsId) {
        AzureCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        AzureCredentials.class,
                        Jenkins.getInstance(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(credentialsId));
        if (creds == null) {
            return new AzureCredentials.ServicePrincipal();
        }
        return creds.data;
    }

    public String getSubscriptionId() {
        return data.subscriptionId.getPlainText();
    }

    public String getClientId() {
        return data.clientId.getPlainText();
    }

    public String getClientSecret() {
        return data.clientSecret.getEncryptedValue();
    }

    public String getPlainClientSecret() {
        return data.clientSecret.getPlainText();
    }

    public String getTenant() {
        return data.getTenant();
    }

    @DataBoundSetter
    public void setTenant(String tenant) {
        this.data.setTenant(tenant);
    }

    /**
     * For backward compatibility.
     *
     * @deprecated use tenant related methods instead.
     */
    @Deprecated
    public String getOauth2TokenEndpoint() {
        return "https://login.windows.net/" + data.getTenant();
    }

    /**
     * Set the Oauth2 token endpoint for backward compatibility only.
     *
     * @param oauth2TokenEndpoint the endpoint value in the form "https://login.windows.net/&lt;TenantId&gt;"
     * @deprecated use tenant related methods instead.
     */
    @DataBoundSetter
    @Deprecated
    public void setOauth2TokenEndpoint(String oauth2TokenEndpoint) {
        this.data.setOauth2TokenEndpoint(oauth2TokenEndpoint);
    }

    public String getAzureEnvionmentName() {
        return data.getAzureEnvironmentName();
    }

    @DataBoundSetter
    public void setAzureEnvironmentName(String azureEnvironmentName) {
        this.data.setAzureEnvironmentName(azureEnvironmentName);
    }

    public String getServiceManagementURL() {
        return data.serviceManagementURL;
    }

    @DataBoundSetter
    public void setServiceManagementURL(String serviceManagementURL) {
        this.data.setServiceManagementURL(serviceManagementURL);
    }

    public String getAuthenticationEndpoint() {
        return data.authenticationEndpoint;
    }

    @DataBoundSetter
    public void setAuthenticationEndpoint(String authenticationEndpoint) {
        this.data.setAuthenticationEndpoint(authenticationEndpoint);
    }

    public String getResourceManagerEndpoint() {
        return data.resourceManagerEndpoint;
    }

    @DataBoundSetter
    public void setResourceManagerEndpoint(String resourceManagerEndpoint) {
        this.data.setResourceManagerEndpoint(resourceManagerEndpoint);
    }

    public String getGraphEndpoint() {
        return data.graphEndpoint;
    }

    @DataBoundSetter
    public void setGraphEndpoint(String graphEndpoint) {
        this.data.setGraphEndpoint(graphEndpoint);
    }

    @Extension
    public static class DescriptorImpl
            extends BaseStandardCredentials.BaseStandardCredentialsDescriptor {
        public DescriptorImpl() {
            super();
            load();
        }

        @Override
        public String getDisplayName() {
            return "Microsoft Azure Service Principal";
        }

        public FormValidation doVerifyConfiguration(
                @QueryParameter String subscriptionId,
                @QueryParameter String clientId,
                @QueryParameter String clientSecret,
                @QueryParameter String tenant,
                @QueryParameter String serviceManagementURL,
                @QueryParameter String authenticationEndpoint,
                @QueryParameter String resourceManagerEndpoint,
                @QueryParameter String graphEndpoint) {

            AzureCredentials.ServicePrincipal servicePrincipal
                    = new AzureCredentials.ServicePrincipal(subscriptionId, clientId, clientSecret);
            servicePrincipal.setTenant(tenant);
            servicePrincipal.setServiceManagementURL(serviceManagementURL);
            servicePrincipal.setAuthenticationEndpoint(authenticationEndpoint);
            servicePrincipal.setResourceManagerEndpoint(resourceManagerEndpoint);
            servicePrincipal.setGraphEndpoint(graphEndpoint);
            try {
                servicePrincipal.validate();
            } catch (ValidationException e) {
                return FormValidation.error(e.getMessage());
            }

            return FormValidation.ok(Messages.Azure_Config_Success());
        }

        public ListBoxModel doFillAzureEnvironmentNameItems() {
            ListBoxModel model = new ListBoxModel();
            model.add(Constants.ENV_AZURE);
            model.add(Constants.ENV_AZURE_CHINA);
            model.add(Constants.ENV_AZURE_GERMANY);
            model.add(Constants.ENV_AZURE_US_GOVERNMENT);
            return model;
        }
    }
}
