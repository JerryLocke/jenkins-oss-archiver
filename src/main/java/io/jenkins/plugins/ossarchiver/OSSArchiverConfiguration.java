package io.jenkins.plugins.ossarchiver;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.util.Collections;
import java.util.List;

@Extension
public class OSSArchiverConfiguration extends GlobalConfiguration {
    private static final String DEFAULT_UPLOAD_FOLDER = "build/${JOB_NAME}/${BUILD_ID}/${BUILD_NUMBER}";

    public static OSSArchiverConfiguration get() {
        return ExtensionList.lookupSingleton(OSSArchiverConfiguration.class);
    }

    private String endPoint;
    private String bucket;
    private String uploadFolder;
    private String credentialsId;

    public OSSArchiverConfiguration() {
        load();
    }

    public String getEndPoint() {
        return endPoint;
    }

    @DataBoundSetter
    public void setEndPoint(String endPoint) {
        this.endPoint = endPoint;
        save();
    }

    public String getBucket() {
        return bucket;
    }

    @DataBoundSetter
    public void setBucket(String bucket) {
        this.bucket = bucket;
        save();
    }

    public String getUploadFolder() {
        return uploadFolder;
    }

    public String getUploadFolderOrDefault() {
        if (StringUtils.isEmpty(uploadFolder)) {
            return DEFAULT_UPLOAD_FOLDER;
        }
        return uploadFolder;
    }

    @DataBoundSetter
    public void setUploadFolder(String uploadFolder) {
        this.uploadFolder = uploadFolder;
        save();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
        save();
    }

    public FormValidation doCheckEndPoint(@QueryParameter String value) {
        if (StringUtils.isEmpty(value)) {
            return FormValidation.warning(Messages.Configuration_MissingEndPoint());
        }
        return FormValidation.ok();
    }

    public FormValidation doCheckBucket(@QueryParameter String value) {
        if (StringUtils.isEmpty(value)) {
            return FormValidation.warning(Messages.Configuration_MissingBucket());
        }
        return FormValidation.ok();
    }

    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
        return new StandardListBoxModel()
                .includeMatchingAs(
                        ACL.SYSTEM,
                        (Item) null,
                        StandardUsernamePasswordCredentials.class,
                        Collections.emptyList(),
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)
                        )
                )
                .includeCurrentValue(credentialsId);
    }

    public FormValidation doCredentialsValidate(
            @QueryParameter String endPoint,
            @QueryParameter String bucket,
            @QueryParameter String credentialsId
    ) {
        try {
            List<StandardUsernamePasswordCredentials> credentialsList = CredentialsProvider.lookupCredentials(
                    StandardUsernamePasswordCredentials.class,
                    (Item) null,
                    null,
                    (DomainRequirement) null
            );
            StandardUsernamePasswordCredentials targetCredentials = null;
            for (StandardUsernamePasswordCredentials credentials : credentialsList) {
                if (credentials.getId().equals(credentialsId)) {
                    targetCredentials = credentials;
                    break;
                }
            }
            if (targetCredentials == null) {
                throw new IllegalArgumentException(Messages.Configuration_CredentialsNotFound(credentialsId));
            }
            OSSClient ossClient = new OSSClient(endPoint,
                    new DefaultCredentialProvider(targetCredentials.getUsername(), targetCredentials.getPassword().getPlainText()),
                    new ClientConfiguration()
            );
            ossClient.listObjects(bucket);
            return FormValidation.ok(Messages.Configuration_CredentialsValidateSuccessful());
        } catch (Exception e) {
            return FormValidation.error(e, Messages.Configuration_CredentialsValidateError());
        }
    }
}
