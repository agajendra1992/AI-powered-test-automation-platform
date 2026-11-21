package io.qaplatform.ui.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.qameta.allure.Step;

import java.nio.file.Path;

public class FilesPage extends BasePage {

    public FilesPage(Page page) {
        super(page);
    }

    @Override
    protected String path() {
        return "/files";
    }

    @Step("Open the files page")
    public FilesPage open() {
        navigate();
        return this;
    }

    @Step("Upload {file}")
    public FilesPage upload(Path file) {
        field("Choose file").setInputFiles(file);
        button("Upload").click();
        return this;
    }

    public Locator uploadResult() {
        return page.getByTestId("upload-result");
    }

    @Step("Download the users report")
    public Path downloadUsersReport() {
        return download(() -> button("Download users report").click());
    }
}
