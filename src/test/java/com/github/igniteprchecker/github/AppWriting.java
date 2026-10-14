package com.github.igniteprchecker.github;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The GitHub of the tests on a checker with GITHUB_TOKEN: its account may write, and GitHub takes each edit. */
public final class AppWriting {
    private AppWriting() {
    }

    /** A GitHub whose app account writes; a comment it posts has no id until a test gives one. */
    public static GithubClient github() {
        GithubClient github = mock(GithubClient.class);
        when(github.writes()).thenReturn(true);
        when(github.canWrite()).thenReturn(true);
        when(github.appEdit(anyInt(), any(), anyLong(), anyString())).thenReturn(true);
        when(github.appReact(anyInt(), any(), anyLong(), anyString())).thenReturn(true);
        when(github.appDelete(anyInt(), any(), anyLong())).thenReturn(true);

        return github;
    }
}
