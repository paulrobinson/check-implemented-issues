//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0, org.kohsuke:github-api:1.112, com.atlassian.jira:jira-rest-java-client-api:5.2.2, com.atlassian.jira:jira-rest-java-client-app:5.2.2, org.json:json:20200518, com.konghq:unirest-java:3.7.04

//REPOS mavencentral,atlassian=https://packages.atlassian.com/maven/repository/public

import com.atlassian.httpclient.api.Request;
import com.atlassian.jira.rest.client.api.AuthenticationHandler;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.*;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import org.kohsuke.github.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Callable;

@Command(name = "run", mixinStandardHelpOptions = true, version = "run 0.1",
        description = "GitHub to Jira issue replicator")
class run implements Callable<Integer> {

    @CommandLine.Option(names = {"-j", "--jira-token"}, description = "The Personal Access Token for authenticating with the JIRA server", required = true)
    private String jiraToken;

    @CommandLine.Option(names = {"-s", "--jira-server"}, description = "The JIRA server to connect to", required = true)
    private String jiraServerURL;

    @CommandLine.Option(names = {"-g", "--gh-token"}, description = "The GitHub API token to use when connecting to the GitHub API", required = true)
    private String githubToken;

    @CommandLine.Option(names = {"-q", "--query"}, description = "The JQL query for JIRA issues to check", required = true)
    private String query;

    private static final String JIRA_GIT_PULL_REQUEST_FIELD_ID = "customfield_12310220";

    public static void main(String... args) {
        int exitCode = new CommandLine(new run()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {

        /*
            Initialise
         */
        final JiraRestClient restClient = new AsynchronousJiraRestClientFactory().create(new URI(jiraServerURL), new BearerHttpAuthenticationHandler(jiraToken));
        GitHub githubClient = new GitHubBuilder().withOAuthToken(githubToken).build();

        HTMLDump htmlDump = new HTMLDump();

        htmlDump.appendHtmlChunk("<table border='1' style='border-collapse:collapse'>");
        htmlDump.appendHtmlChunk("<tr><th>Issue</th><th>Summary</th><th>GH Milestone</th><th>Status</th></tr>");

        SearchResult searchResultsAll = restClient.getSearchClient().searchJql(query, 1000, 0, null).claim();
        System.out.println(searchResultsAll.getTotal());
        for (Issue issue :searchResultsAll.getIssues()) {

            String htmlRow = "";
            htmlRow += "<tr>";
            htmlRow +=     "<td><a href='https://issues.redhat.com/browse/" +  issue.getKey() + "'>" + issue.getKey() + "</a></td>";
            htmlRow +=     "<td>" + issue.getSummary() + "</td>";

            Object prUrl = issue.getField(JIRA_GIT_PULL_REQUEST_FIELD_ID).getValue();
            if (prUrl != null) {
                GHPullRequest ghPr =  githubClient.getOrganization("quarkusio").getRepository("quarkus").getPullRequest(HACK_getPrNumber(prUrl));
                if (ghPr.getMilestone() == null) {
                    htmlRow +=     "<td>MISSING MILESTONE ON PR</td>";
                } else {
                    htmlRow +=     "<td>" + ghPr.getMilestone().getTitle() + "</td>";
                }

            } else {
                htmlRow +=     "<td>MISSING PR LINK</td>";
            }

            htmlRow +=     "<td>" + issue.getStatus().getName() + "</td>";
            htmlRow += "</tr>";

            htmlDump.appendHtmlChunk(htmlRow);
        }

        htmlDump.appendHtmlChunk("</table>");

        htmlDump.dump(new File("output.html"));

        return 0;
    }

    public int HACK_getPrNumber(Object prUrl) {
        String prUrlString = prUrl.toString();
        String prNumber = prUrlString.substring(prUrlString.lastIndexOf("/")+1, prUrlString.lastIndexOf("\""));
        return Integer.parseInt(prNumber);
    }

    public static class HTMLDump {

        private String html;
        private boolean closed = false;

        public HTMLDump() {
            this.html = "<HTML><BODY>";
        }

        public void appendHtmlChunk(String htmlChunk) {

            if (closed) throw new RuntimeException("This HTML Dump is already closed, can't append more HTML to it");

            this.html += htmlChunk;
        }

        public void dump(File outputFile) {

            if (!closed) {
                this.html += "</Body></HTML>";
                closed = true;
            }

            try {
                BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));
                writer.write(html);
                writer.close();
            } catch (IOException e) {
                throw new RuntimeException("Error writing HTML dump to " + outputFile.getAbsolutePath(), e);
            }
        }
    }

    public static class BearerHttpAuthenticationHandler implements AuthenticationHandler {

        private static final String AUTHORIZATION_HEADER = "Authorization";
        private final String token;

        public BearerHttpAuthenticationHandler(final String token) {
            this.token = token;
        }

        @Override
        public void configure(Request.Builder builder) {
            builder.setHeader(AUTHORIZATION_HEADER, "Bearer " + token);
        }
    }

}
