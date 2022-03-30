package io.jenkins.plugins.ossarchiver;

import hudson.model.Run;
import jenkins.model.RunAction2;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class OSSArchiverAction implements RunAction2 {
    private final Map<String, List<Pair<String, String>>> result;
    private Run<?, ?> run;

    public OSSArchiverAction(Map<String, List<Pair<String, String>>> result) {
        this.result = result == null ? Collections.emptyMap() : result;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    public Map<String, List<Pair<String, String>>> getResult() {
        return result;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    @Override
    public String getIconFileName() {
        return "save.gif";
    }

    @Override
    public String getDisplayName() {
        return Messages.Title();
    }

    @Override
    public String getUrlName() {
        return "ossArchiver";
    }
}
