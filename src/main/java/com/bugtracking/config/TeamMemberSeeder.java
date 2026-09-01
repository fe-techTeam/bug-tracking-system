package com.bugtracking.config;

import com.bugtracking.service.TeamMemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The team, seeded on startup.
 *
 * <p>Matched on email and added only if missing, so this is safe to re-run and
 * new names can simply be appended to the list. Anyone added through the Team
 * page is left alone, and deactivating somebody here does not bring them back.
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
            int added = service.addMissing(TEAM);
            if (added > 0) {
                log.info("Added {} team member(s) to the team_members table.", added);
            }
        };
    }
}
