package com.bebebe.agent.tools.web;

import java.util.List;

public interface SearchProvider {

    String name();

    List<SearchResult> search(String query, int limit);
}
