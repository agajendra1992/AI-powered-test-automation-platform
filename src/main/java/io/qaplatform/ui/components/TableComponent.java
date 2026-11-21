package io.qaplatform.ui.components;

import com.microsoft.playwright.Locator;

import java.util.List;

/**
 * Generic data table. Cells are addressed by column header text, so tests survive column reordering.
 */
public class TableComponent {

    private final Locator table;

    public TableComponent(Locator table) {
        this.table = table;
    }

    public Locator rows() {
        return table.locator("tbody tr");
    }

    /** The single row containing the given text (e.g. a unique email). */
    public Locator rowContaining(String text) {
        return rows().filter(new Locator.FilterOptions().setHasText(text));
    }

    public Locator cell(Locator row, String columnHeader) {
        List<String> headers = table.locator("thead th").allTextContents();
        int index = headers.indexOf(columnHeader);
        if (index < 0) {
            throw new IllegalArgumentException("No column '" + columnHeader + "' in " + headers);
        }
        return row.locator("td").nth(index);
    }
}
