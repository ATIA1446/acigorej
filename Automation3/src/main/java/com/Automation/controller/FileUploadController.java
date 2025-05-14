package com.Automation.controller;

import com.Automation.service.FileProcessingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

@Controller
public class FileUploadController {

    private final FileProcessingService fileProcessingService;
    private boolean firstVisit = true;

    public FileUploadController(FileProcessingService fileProcessingService) {
        this.fileProcessingService = fileProcessingService;
    }

    @GetMapping("/")
    public String welcomePage(Model model) {
        model.addAttribute("showAnimation", firstVisit);
        firstVisit = false;
        return "welcome";
    }

    @GetMapping("/upload")
    public String uploadForm() {
        return "upload";
    }

    @PostMapping("/process")
    public String processFile(@RequestParam("file") MultipartFile file, 
                            HttpSession session, 
                            Model model) {
        try {
            String processedFilePath = fileProcessingService.processUploadedFile(file);
            session.setAttribute("processedFile", processedFilePath);
            model.addAttribute("fileName", file.getOriginalFilename());
            return "processing";
        } catch (Exception e) {
            model.addAttribute("error", "Error processing file: " + e.getMessage());
            return "error";
        }
    }

    @GetMapping("/download")
    public void downloadFile(HttpSession session, HttpServletResponse response) {
        try {
            String filePath = (String) session.getAttribute("processedFile");
            fileProcessingService.downloadProcessedFile(filePath, response);
            
            // Clean up session after download
            session.removeAttribute("processedFile");
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            try {
                response.getWriter().write("Error downloading file: " + e.getMessage());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
}