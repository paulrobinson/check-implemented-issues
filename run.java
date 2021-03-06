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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    @CommandLine.Option(names = {"-i", "--ignore-with-version-prefix"}, description = "Ignore issues with the specified prefix", required = false)
    private String ignoreWithPrefix;

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
        htmlDump.appendHtmlChunk("<tr><th>JIRA Issue</th><th>Status</th><th>GH Issue</th><th>GH Milestone</th><th>Summary</th></tr>");

        SearchResult searchResultsAll = restClient.getSearchClient().searchJql(query, 1000, 0, null).claim();
        System.out.println(searchResultsAll.getTotal());
        for (Issue issue :searchResultsAll.getIssues()) {

            String htmlRow = "";
            htmlRow += "<tr>";
            htmlRow +=     "<td><a href='https://issues.redhat.com/browse/" +  issue.getKey() + "'>" + issue.getKey() + "</a></td>";
            htmlRow +=     "<td>" + issue.getStatus().getName() + "</td>";


            Object prUrl = issue.getField(JIRA_GIT_PULL_REQUEST_FIELD_ID).getValue();
            if (isMultipleLinks(prUrl)) {
                htmlRow +=     "<td>MULTIPLE PR LINKS</td>";
                htmlRow +=     "<td>N/A</td>";
            }
            else if (isPullRequestLink(prUrl)) {

                GHPullRequest ghPr =  githubClient.getOrganization("quarkusio").getRepository("quarkus").getPullRequest(HACK_getPrNumber(prUrl));
                htmlRow +=     "<td><a href='" + ghPr.getHtmlUrl() + "'>" +  "#" + HACK_getPrNumber(prUrl) + "</a></td>";
                if (ghPr.getMilestone() == null) {
                    htmlRow +=     "<td>NO MILESTONE ON PR</td>";
                } else {
                    htmlRow +=     "<td>" + ghPr.getMilestone().getTitle() + "</td>";

                    //Check if PR has a Milestone to be ignored
                    if (ignoreWithPrefix != null) {
                        if (ghPr.getMilestone().getTitle().startsWith(ignoreWithPrefix)) {
                            System.out.println("[Ignoring] " + ghPr.getHtmlUrl() + ", Milestone: " + ghPr.getMilestone().getTitle());
                            continue; //Ignore this row, as the Milestone matches the ignored prefix
                        }
                    }
                }
            } else {
                htmlRow +=     "<td>MISSING PR LINK</td>";
                htmlRow +=     "<td>N/A</td>";
            }


            htmlRow +=     "<td>" + issue.getSummary() + "</td>";
            htmlRow += "</tr>";

            htmlDump.appendHtmlChunk(htmlRow);
        }

        htmlDump.appendHtmlChunk("</table>");

        htmlDump.dump(new File("output.html"));

        return 0;
    }

    public boolean isMultipleLinks(Object url) {
        if (url == null) return false;
        return countOccurances(url.toString(), "http") > 1;
    }

    public int countOccurances(String input, String match) {
        int index = input.indexOf(match);
        int count = 0;
        while (index != -1) {
            count++;
            input = input.substring(index + 1);
            index = input.indexOf(match);
        }
        return count;
    }

    public boolean isPullRequestLink(Object url) {
        if (url == null) return false;
        if (isMultipleLinks(url)) return false;
        try {
            String strURL = url.toString();
            strURL = strURL.substring(2); //remove preceding: "[
            strURL = strURL.substring(0, strURL.length() - 2); //remove trailing: ]"
            strURL = strURL.replace("\\", ""); //remove escape chars
            URL parsedURL = new URL(strURL);
            return parsedURL.getPath().startsWith("/quarkusio/quarkus/pull/");
        } catch (MalformedURLException e) {
            return false;
        }
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
