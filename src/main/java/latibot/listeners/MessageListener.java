package latibot.listeners;

import latibot.LatiBot;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MessageListener extends ListenerAdapter {

    // this thing is getting really stupid and so am i, but it does seem
    // to consistently work even if its probably overdone. also chatgpt
    // can't seem to figure out how to suggest any changes to it that wont
    // also break it, but honestly i dont really blame it
    private static final Pattern urlRegex = Pattern.compile(
            "(?<before>.*)(?<fullLink>https?://(www\\.)?(?<domain>[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6})\\b[-a-zA-Z0-9()@:%_+.~#&/=]+)[-a-zA-Z0-9()@:%_+.~#&?/=]*(?<after>.*)");
    private static final HashMap<String, List<String>> domains = new HashMap<>();

    public static HashMap<String, List<String>> getDomains() { return domains; }

    public static List<Long> userBlacklist = new ArrayList<>();

    public static boolean useWebhooks;

    static {
        //> Loading URL Replacements
        try (Stream<String> urlReplacements = Files.lines(Path.of("UrlReplacements.txt"))) {
            urlReplacements.map(line -> line.split("\\|")).forEach(parts -> {
                domains.put(parts[0], Arrays.asList(parts[1].split("\\^")));
            });
        } catch (IOException e) {
            LatiBot.LOG.error("Error reading UrlReplacements.txt", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return; //> must be a user message
        Member member = event.getMember();
        Message message = event.getMessage();
        String content = message.getContentRaw();

        //> 420 & 69
        if (content.matches(".*\\b4:?20\\b.*") || content.matches(".*\\b69\\b.*")) {
            event.getChannel().sendMessage("nice").setSuppressedNotifications(true).queue();
            return;
        }

        //> check blacklist
        if (userBlacklist.contains(member.getIdLong())) return;

        //> get the reply string using the main
        ReplyInfo r = buildReplyString(content, 0);
        if (r == null) return;
        String reply = r.content;

        // if msg has content
        if (!reply.isEmpty()) {
            if (useWebhooks) {
                try {
                    // create webhook
                    Webhook webhook = message.getChannel().asTextChannel().createWebhook("LatiBot Url Replacer").complete();

                    // send the msg
                    webhook.sendMessage(reply.toString())
                            .setUsername(member.getEffectiveName())
                            .setAvatarUrl(member.getEffectiveAvatarUrl())
                            .setSuppressedNotifications(true)
                            .queue();
                    message.delete().queue();
                    webhook.delete().queue();
                } catch (PermissionException pe) {
                    LatiBot.LOG.info("Missing MANAGE_WEBHOOKS permission in channel: {}", message.getChannel());
                    message.getChannel()
                            .sendMessage("bro i tried but my mom said no (im missing the manage webhooks perm in here)")
                            .queue();
                }
            } else {
                //> supress the embed on the original then send a msg to replace the embed
                //> yes this calls buildReplyString again, but consider: i dont care -MH
                message.suppressEmbeds(true).queue(
                        _v -> event.getChannel().sendMessage(reply).setSuppressedNotifications(true).setSuppressEmbeds(false).queue(
                                m -> recurseCheckEmbed(m, event, content, 0, r.replaceCount)));
            }
        }
    }

    private record ReplyInfo(String content, int index, int replaceCount, String domain) {}

    private ReplyInfo buildReplyString(String content, int index) {
        Matcher matcher = urlRegex.matcher(content);
        StringBuilder reply = new StringBuilder();
        int count = 0;
        String domain = "";
        while (matcher.find()) {
            count++;
            domain = matcher.group("domain");
            List<String> replacements = domains.get(domain);
            if (replacements != null) {
                index = index % replacements.size();
                if (useWebhooks) {
                    reply.append(matcher.group("before"))
                            .append("<").append(matcher.group("fullLink")).append("> [_](")
                            .append(matcher.group("fullLink").replace(domain, replacements.get(index) == null ? replacements.getLast() : replacements.get(index)))
                            .append(domain == "x.com" ? "/en" : "") // manually tested it, all the x.com replacements have a working implementation of '/en' 
                            .append(")").append(matcher.group("after"));
                } else {
                    reply.append("[_](")
                            .append(matcher.group("fullLink").replace(domain, replacements.get(index) == null ? replacements.getLast() : replacements.get(index)))
                            .append(domain == "x.com" ? "/en" : "") // manually tested it, all the x.com replacements have a working implementation of '/en' 
                            .append(")");
                    // Spoiler Check, odd number of markers before & after link required
                    if (matcher.group("before").split("||").length % 2 == 0
                            && matcher.group("after").split("||").length % 2 == 0) {
                        reply.insert(0, "||").append("||");
                    }
                    reply.insert(0,"\uD83D\uDD17");
                }
            } else {
                return null;
            }
        }
        return new ReplyInfo(reply.toString(), index, count, domain);
    }


    private void recurseCheckEmbed(final Message msg, final MessageReceivedEvent event, final String content, int index, int replaceCount) {
        //> max retries
        if (index > 10) {
            LatiBot.LOG.info("Embed failed with message: '{}', too many retries!", msg.getContentRaw());
            msg.delete().queue();
            event.getMessage().suppressEmbeds(false).queue();
            return;
        }
        event.getJDA().getRateLimitPool().schedule(() -> {
            int c = -1;
            Message m = null;
            if ((c = (m = event.getChannel().retrieveMessageById(msg.getIdLong()).complete()).getEmbeds().size()) < replaceCount) {
                LatiBot.LOG.info("Embed failed with message: '{}', expected {} embeds but got {}, retrying...", m.getContentRaw(), replaceCount, c);
                ReplyInfo r = buildReplyString(content, index + 1);
                if (r == null) return;
                m.editMessage(r.content).queue(t -> recurseCheckEmbed(t, event, content, index + 1, r.replaceCount));
            }
        }, 5, TimeUnit.SECONDS);
    }


    public static boolean saveUrlReplacements() {
        String filePath = "UrlReplacements.txt";

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (String domain : domains.keySet()) {
                writer.write(domain + "|" + String.join("^", domains.get(domain)));
                writer.newLine();
            }
            LatiBot.LOG.info("Changes were saved to UrlReplacements.txt");
            return true;
        } catch (IOException e) {
            LatiBot.LOG.error("Error writing to UrlReplacements.txt", e);
            return false;
        }
    }
}
