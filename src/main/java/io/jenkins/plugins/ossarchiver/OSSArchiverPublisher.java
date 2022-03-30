package io.jenkins.plugins.ossarchiver;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OSSArchiverPublisher extends Recorder implements SimpleBuildStep {
    private static final Pattern ABSOLUTE_PREFIX_PATTERN = Pattern.compile("^(\\\\\\\\|(?:[A-Za-z]:)?[\\\\/])[\\\\/]*");

    private List<ArtifactConfig> artifacts;

    @DataBoundConstructor
    public OSSArchiverPublisher(List<ArtifactConfig> artifacts) {
        this.artifacts = artifacts;
    }

    @DataBoundSetter
    public void setArtifacts(List<ArtifactConfig> artifacts) {
        this.artifacts = artifacts;
    }

    public List<ArtifactConfig> getArtifacts() {
        return artifacts;
    }

    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull EnvVars env,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener
    ) throws InterruptedException, IOException {
        OSSArchiverLogger logger = new OSSArchiverLogger(listener);
        try {
            Map<FilePath, List<FilePath>> actualArtifacts = findActualArtifacts(workspace);
            if (actualArtifacts.isEmpty()) {
                logger.warn("No artifacts matched, return");
                return;
            }

            OSSArchiverConfiguration configuration = parseConfiguration();
            String uploadFolder = configuration.getUploadFolderOrDefault();
            String actualUploadFolder = env.expand(uploadFolder);

            StandardUsernamePasswordCredentials credentials = findCredentials(configuration.getCredentialsId(), run);

            Map<String, List<Pair<String, String>>> result = uploadFiles(
                    configuration,
                    actualArtifacts,
                    workspace,
                    actualUploadFolder,
                    credentials,
                    logger
            );

            run.addAction(new OSSArchiverAction(result));
        } catch (Exception e) {
            logger.error("Publish exception", e);
        }
    }

    private Map<FilePath, List<FilePath>> findActualArtifacts(FilePath workspace) throws IOException, InterruptedException {
        Map<FilePath, List<FilePath>> actualArtifacts = new LinkedHashMap<>(artifacts.size());
        for (ArtifactConfig config : artifacts) {
            String folder = config.folder;
            if (folder.startsWith("/")) {
                continue;
            }
            String filename = config.filename;
            if (StringUtils.isBlank(filename)) {
                filename = "";
            }
            FilePath folderFile = new FilePath(workspace, folder);
            if (!folderFile.isDirectory()) {
                continue;
            }
            FilePath[] files = folderFile.list(filename);
            if (files.length == 0) {
                continue;
            }
            actualArtifacts.put(folderFile, Arrays.asList(files));
        }
        return actualArtifacts;
    }

    private OSSArchiverConfiguration parseConfiguration() {
        OSSArchiverConfiguration configuration = Jenkins.get().getDescriptorByType(OSSArchiverConfiguration.class);
        if (configuration == null || StringUtils.isEmpty(configuration.getBucket())
                || StringUtils.isEmpty(configuration.getEndPoint()) || StringUtils.isEmpty(configuration.getCredentialsId())) {
            throw new IllegalArgumentException("Invalid configuration");
        }
        return configuration;
    }

    private StandardUsernamePasswordCredentials findCredentials(String credentialsId, Run<?, ?> run) {
        StandardUsernamePasswordCredentials credentials = CredentialsProvider.findCredentialById(credentialsId, StandardUsernamePasswordCredentials.class, run);
        if (credentials == null) {
            throw new IllegalArgumentException("Credentials not found: id=" + credentialsId);
        }
        return credentials;
    }

    private Map<String, List<Pair<String, String>>> uploadFiles(
            OSSArchiverConfiguration configuration,
            Map<FilePath, List<FilePath>> actualArtifacts,
            FilePath workspace,
            String uploadFolder,
            StandardUsernamePasswordCredentials credentials,
            OSSArchiverLogger logger
    ) {
        OSSClient ossClient = new OSSClient(configuration.getEndPoint(),
                new DefaultCredentialProvider(credentials.getUsername(), credentials.getPassword().getPlainText()),
                new ClientConfiguration()
        );
        Map<String, List<Pair<String, String>>> result = new LinkedHashMap<>(actualArtifacts.size());
        for (Map.Entry<FilePath, List<FilePath>> entry : actualArtifacts.entrySet()) {
            List<Pair<String, String>> items = new ArrayList<>(entry.getValue().size());
            FilePath folderPath = entry.getKey();
            String folder = getRelativePath(folderPath, workspace);
            if (folder == null) {
                continue;
            }
            for (FilePath filePath : entry.getValue()) {
                String file = getRelativePath(filePath, folderPath);
                if (file == null) {
                    continue;
                }
                String key = normalize(uploadFolder + "/" + folder.replace(File.separator, "/") + "/" + file.replace(File.separator, "/"));
                try (InputStream inputStream = filePath.read()) {
                    logger.info("Uploading: " + key);
                    ossClient.putObject(configuration.getBucket(), key, inputStream);
                    String url = makeUrl(ossClient, configuration.getBucket(), key);
                    Pair<String, String> item = new ImmutablePair<>(file, url);
                    items.add(item);
                } catch (Exception e) {
                    logger.warn("Upload failed: " + key, e);
                }
            }
            result.put(folder, items);
        }
        return result;
    }

    private String getRelativePath(FilePath file, FilePath parent) {
        String filePath = file.getRemote();
        String parentPath = parent.getRemote();
        if (filePath.startsWith(parentPath)) {
            String result = filePath.substring(parentPath.length());
            if (result.startsWith(File.separator)) {
                return result.substring(1);
            }
            return result;
        }
        return null;
    }

    /**
     * @see FilePath#normalize(java.lang.String)
     */
    private String normalize(@NonNull String path) {
        StringBuilder buf = new StringBuilder();
        // Check for prefix designating absolute path
        Matcher m = ABSOLUTE_PREFIX_PATTERN.matcher(path);
        if (m.find()) {
            buf.append(m.group(1));
            path = path.substring(m.end());
        }
        boolean isAbsolute = buf.length() > 0;
        // Split remaining path into tokens, trimming any duplicate or trailing separators
        List<String> tokens = new ArrayList<>();
        int s = 0, end = path.length();
        for (int i = 0; i < end; i++) {
            char c = path.charAt(i);
            if (c == '/' || c == '\\') {
                tokens.add(path.substring(s, i));
                s = i;
                // Skip any extra separator chars
                //noinspection StatementWithEmptyBody
                while (++i < end && ((c = path.charAt(i)) == '/' || c == '\\'))
                    ;
                // Add token for separator unless we reached the end
                if (i < end) tokens.add(path.substring(s, s + 1));
                s = i;
            }
        }
        if (s < end) tokens.add(path.substring(s));
        // Look through tokens for "." or ".."
        for (int i = 0; i < tokens.size(); ) {
            String token = tokens.get(i);
            if (token.equals(".")) {
                tokens.remove(i);
                if (tokens.size() > 0)
                    tokens.remove(i > 0 ? i - 1 : i);
            } else if (token.equals("..")) {
                if (i == 0) {
                    // If absolute path, just remove: /../something
                    // If relative path, not collapsible so leave as-is
                    tokens.remove(0);
                    if (tokens.size() > 0) token += tokens.remove(0);
                    if (!isAbsolute) buf.append(token);
                } else {
                    // Normalize: remove something/.. plus separator before/after
                    i -= 2;
                    for (int j = 0; j < 3; j++) tokens.remove(i);
                    if (i > 0) tokens.remove(i - 1);
                    else if (tokens.size() > 0) tokens.remove(0);
                }
            } else
                i += 2;
        }
        // Recombine tokens
        for (String token : tokens) buf.append(token);
        if (buf.length() == 0) buf.append('.');
        return buf.toString();
    }

    private String makeUrl(OSSClient ossClient, String bucket, String key) {
        URI uri = ossClient.getEndpoint();
        return uri.getScheme() + "://" + bucket + "." + uri.getAuthority() + "/" + key;
    }

    public static class ArtifactConfig extends AbstractDescribableImpl<ArtifactConfig> implements Serializable {
        private String folder;
        private String filename;

        @DataBoundConstructor
        public ArtifactConfig(String folder, String filename) {
            this.folder = folder;
            this.filename = filename;
        }

        public String getFolder() {
            return folder;
        }

        @DataBoundSetter
        public void setFolder(String folder) {
            this.folder = folder;
        }

        public String getFilename() {
            return filename;
        }

        @DataBoundSetter
        public void setFilename(String filename) {
            this.filename = filename;
        }

        public FormValidation doCheckFolder(@QueryParameter String value) {
            return checkPath(value, Messages.Publisher_WillUploadWorkspace());
        }

        public FormValidation doCheckFilename(@QueryParameter String value) {
            return checkPath(value, Messages.Publisher_WillUploadWholeFolder());
        }

        private FormValidation checkPath(String value, String emptyMessage) {
            if (StringUtils.isBlank(value)) {
                return FormValidation.warning(emptyMessage);
            }
            if (value.startsWith("/")) {
                return FormValidation.error(Messages.Publisher_UseRelativePathFormat());
            }
            return FormValidation.ok();
        }

        @Symbol("artifact")
        @Extension
        public static class DescriptorImpl extends Descriptor<ArtifactConfig> {
            @NonNull
            @Override
            public String getDisplayName() {
                return super.getDisplayName();
            }
        }
    }

    @Symbol("ossArchiver")
    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.Title();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
