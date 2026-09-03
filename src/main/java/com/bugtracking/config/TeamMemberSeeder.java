package com.bugtracking.config;

import com.bugtracking.service.TeamMemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The team a brand new database starts with.
 *
 * <p>Only ever into an <em>empty</em> table, for the same reason as
 * {@link ProjectSeeder}: filling in whatever was missing on every boot meant
 * somebody removed from the team reappeared at the next restart. Once there is
 * one person here this does nothing.
 *
 * <p>So a name added to the list below only reaches a database with nobody in
 * it. Everywhere else, Settings &gt; Team — or the board's team drawer — is
 * how somebody joins, and that writes to this same table.
 */
@Configuration
public class TeamMemberSeeder {

    private static final Logger log = LoggerFactory.getLogger(TeamMemberSeeder.class);

    private static final List<String[]> TEAM = List.of(
            new String[]{"Aakash Madkaikar", "aakash@firsteconomy.com"},
            new String[]{"Ajay Gosavi", "ajay@firsteconomy.com"},
            new String[]{"Ankita Verma", "ankita@firsteconomy.com"},
            new String[]{"Arjun R", "arjun@firsteconomy.com"},
            new String[]{"Bharatesh Shetty", "bharatesh@firsteconomy.com"},
            new String[]{"Binish B", "binish@firsteconomy.com"},
            new String[]{"Dakshil Talsaniya", "dakshil@firsteconomy.com"},
            new String[]{"Gagan Jain", "gagan@firsteconomy.com"},
            new String[]{"Harshad Madaye", "harshadmadaye@firsteconomy.com"},
            new String[]{"Hasnain Shaikh", "hasnain@firsteconomy.com"},
            new String[]{"Leona Mendes", "leona@firsteconomy.com"},
            new String[]{"Nishana R", "nishana@firsteconomy.com"},
            new String[]{"Riddhi Dankhara", "riddhi@firsteconomy.com"},
            new String[]{"Sanjay Chauhan", "sanjay@firsteconomy.com"},
            new String[]{"Swapnil Kajale", "swapnilkajale@firsteconomy.com"},
            new String[]{"Vatsal Motiani", "vatsal@firsteconomy.com"},
            new String[]{"Viveck Raj", "viveck@firsteconomy.com"},
            new String[]{"Vivek Gholap", "vivek@firsteconomy.com"});

    @Bean
    CommandLineRunner seedTeam(TeamMemberService service) {
        return args -> {
            if (!service.all().isEmpty()) {
                return;                          // somebody's roster; leave it alone
            }
            int added = service.addMissing(TEAM);
            if (added > 0) {
                log.info("Seeded {} team member(s) into an empty team_members table.", added);
            }
        };
    }
}
