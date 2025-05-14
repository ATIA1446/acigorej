package com.Automation.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FileProcessingService {

    private static final List<String> EXPECTED_HEADERS = Arrays.asList(
            "Serial No.",
            "Business Unit",
            "Party Code",
            "Party Name",
            "Zone",
            "Region",
            "Case No",
            "Court",
            "Stage",
            "Last Date",
            "Next Date",
            "Type of Case",
            "Brief Facts",
            "Case Filling District",
            "Previous Status",
            "Cheque Amount (BDT in Lakh)"
    );

    private static final String BRIEF_FACTS_VALUE = "The accused provided a cheque of BDT 30.09 Lac towards discharge of its liability. The cheque was presented and got dishonoured subsequently. Hence the complaint was filed as the 138 notice was not honoured by him.";

    public String processUploadedFile(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            Workbook workbook = new XSSFWorkbook(inputStream);
            Map<String, Map<String, Set<String>>> partyData = processWorkbook(workbook);
            
            Path tempFile = Files.createTempFile("processed_", ".xlsx");
            try (FileOutputStream outputStream = new FileOutputStream(tempFile.toFile())) {
                Workbook outputWorkbook = generateOutputWorkbook(partyData);
                outputWorkbook.write(outputStream);
                outputWorkbook.close();
            }
            
            return tempFile.toString();
        }
    }

    public void downloadProcessedFile(String filePath, HttpServletResponse response) throws IOException {
        if (filePath == null || !Files.exists(Path.of(filePath))) {
            throw new FileNotFoundException("Processed file not found or expired");
        }
        
        Path file = Path.of(filePath);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=Processed_Report.xlsx");
        
        Files.copy(file, response.getOutputStream());
        response.flushBuffer();
        
        // Clean up - delete the temp file
        Files.deleteIfExists(file);
    }

    private Map<String, Map<String, Set<String>>> processWorkbook(Workbook workbook) {
        Map<String, Map<String, Set<String>>> partyCodeToFieldValuesMap = new LinkedHashMap<>();

        for (Sheet sheet : workbook) {
            if (sheet == null) continue;

            int headerRowNum = findHeaderRow(sheet);
            if (headerRowNum == -1) continue;

            Row headerRow = sheet.getRow(headerRowNum);
            Map<Integer, String> colIndexToHeader = mapColumnIndexes(headerRow);

            for (int rowIndex = headerRowNum + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isRowEmpty(row)) continue;

                String partyCode = getPartyCode(row, colIndexToHeader);
                if (partyCode.isEmpty()) continue;

                if (!partyCodeToFieldValuesMap.containsKey(partyCode)) {
                    initializePartyRecord(partyCodeToFieldValuesMap, partyCode);
                }

                updatePartyRecord(partyCodeToFieldValuesMap.get(partyCode), row, colIndexToHeader);
            }
        }

        if (partyCodeToFieldValuesMap.isEmpty()) {
            throw new RuntimeException("No valid data found in the file.");
        }
        
        return partyCodeToFieldValuesMap;
    }
    
    private Workbook generateOutputWorkbook(Map<String, Map<String, Set<String>>> partyData) {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Processed Data");

        // Create styles
        CellStyle wrapStyle = workbook.createCellStyle();
        wrapStyle.setWrapText(true);
        wrapStyle.setVerticalAlignment(VerticalAlignment.TOP); // Align to top
        wrapStyle.setAlignment(HorizontalAlignment.LEFT); // Align to left

        CellStyle defaultStyle = workbook.createCellStyle();
        defaultStyle.setVerticalAlignment(VerticalAlignment.TOP);
        defaultStyle.setAlignment(HorizontalAlignment.LEFT);

        int briefFactsColIndex = EXPECTED_HEADERS.indexOf("Brief Facts");

        // Create header row
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < EXPECTED_HEADERS.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(EXPECTED_HEADERS.get(i));
            cell.setCellStyle(defaultStyle);
        }

        // Process data
        int rowNum = 1;
        for (Map.Entry<String, Map<String, Set<String>>> entry : partyData.entrySet()) {
            Map<String, Set<String>> record = entry.getValue();
            
            // Format case numbers with proper commas
            Set<String> formattedCaseNos = record.get("Case No").stream()
                .map(this::formatCaseNumbers)
                .collect(Collectors.toSet());
                
            if (formattedCaseNos.isEmpty()) {
                Row row = sheet.createRow(rowNum++);
                fillRow(row, record, rowNum-1, briefFactsColIndex, wrapStyle, defaultStyle);
                continue;
            }
            
            List<String> caseNoList = new ArrayList<>(formattedCaseNos);
            List<String> courts = new ArrayList<>(record.get("Court"));
            List<String> stages = new ArrayList<>(record.get("Stage"));
            List<String> lastDates = new ArrayList<>(record.get("Last Date"));
            List<String> nextDates = new ArrayList<>(record.get("Next Date"));
            
            for (int i = 0; i < caseNoList.size(); i++) {
                Row row = sheet.createRow(rowNum++);
                Map<String, Set<String>> rowRecord = new LinkedHashMap<>();
                
                // Copy all fields
                for (String header : EXPECTED_HEADERS) {
                    rowRecord.put(header, new LinkedHashSet<>(record.get(header)));
                }
                
                // Update with current case data
                rowRecord.get("Case No").clear();
                rowRecord.get("Case No").add(caseNoList.get(i));
                
                if (i < courts.size()) {
                    rowRecord.get("Court").clear();
                    rowRecord.get("Court").add(courts.get(i));
                }
                if (i < stages.size()) {
                    rowRecord.get("Stage").clear();
                    rowRecord.get("Stage").add(stages.get(i));
                }
                if (i < lastDates.size()) {
                    rowRecord.get("Last Date").clear();
                    rowRecord.get("Last Date").add(lastDates.get(i));
                }
                if (i < nextDates.size()) {
                    rowRecord.get("Next Date").clear();
                    rowRecord.get("Next Date").add(nextDates.get(i));
                }
                
                fillRow(row, rowRecord, rowNum-1, briefFactsColIndex, wrapStyle, defaultStyle);
            }
        }

        // Set column widths
        for (int i = 0; i < EXPECTED_HEADERS.size(); i++) {
            if (i == briefFactsColIndex) {
                sheet.setColumnWidth(i, 15000); // Wider for Brief Facts
            } else {
                sheet.autoSizeColumn(i);
            }
        }
        
        return workbook;
    }

    private void fillRow(Row row, Map<String, Set<String>> record, int serialNo, 
                        int briefFactsColIndex, CellStyle wrapStyle, CellStyle defaultStyle) {
        for (int i = 0; i < EXPECTED_HEADERS.size(); i++) {
            String header = EXPECTED_HEADERS.get(i);
            Cell cell = row.createCell(i);
            
            if ("Serial No.".equals(header)) {
                cell.setCellValue(serialNo);
                cell.setCellStyle(defaultStyle);
            } else {
                String value = record.get(header).stream()
                    .filter(v -> !v.isEmpty())
                    .collect(Collectors.joining(", "));
                
                cell.setCellValue(value);
                
                if (i == briefFactsColIndex) {
                    cell.setCellStyle(wrapStyle);
                    // Adjust row height for wrapped text
                    row.setHeightInPoints((value.length() / 50 + 1) * 15);
                } else {
                    cell.setCellStyle(defaultStyle);
                }
            }
        }
    }

    private String formatCaseNumbers(String caseNo) {
        if (caseNo == null || caseNo.trim().isEmpty()) {
            return "";
        }
        
        // First clean the input - remove extra spaces and normalize
        String cleaned = caseNo.trim()
                              .replaceAll("\\s+", "")
                              .replaceAll(",+", ",")
                              .replaceAll(";+", ",")
                              .replaceAll("\\|+", ",");
        
        // Pattern to match case numbers like SC-17653/22 or CR-647/21
        // This matches:
        // - 2-3 uppercase letters
        // - followed by hyphen
        // - followed by digits
        // - followed by slash
        // - followed by 2-4 digits
        String caseNumberPattern = "([A-Z]{2,3}-\\d+/\\d{2,4})";
        
        // Split and join with commas where needed
        String formatted = cleaned.replaceAll(
            caseNumberPattern + "(?=" + caseNumberPattern + ")",
            "$1,"
        );
        
        // Handle any remaining stuck-together cases after first pass
        formatted = formatted.replaceAll(
            caseNumberPattern + "(?=" + caseNumberPattern + ")",
            "$1,"
        );
        
        // Clean up any double commas that might have been created
        formatted = formatted.replace(",,", ",");
        
        return formatted;
    }


    private String getPartyCode(Row row, Map<Integer, String> colIndexToHeader) {
        int partyCodeColIndex = getColumnIndex(colIndexToHeader, "partycode");
        String partyCode = partyCodeColIndex >= 0 ? getCellValueAsString(row.getCell(partyCodeColIndex)) : "";
        return formatPartyCode(partyCode);
    }

    private void initializePartyRecord(Map<String, Map<String, Set<String>>> map, String partyCode) {
        Map<String, Set<String>> record = new LinkedHashMap<>();
        for (String header : EXPECTED_HEADERS) {
            record.put(header, new LinkedHashSet<>());
        }
        
        record.get("Business Unit").add("ACI");
        record.get("Region").add("Central");
        record.get("Type of Case").add("138 NI Act");
        record.get("Brief Facts").add(BRIEF_FACTS_VALUE);
        
        map.put(partyCode, record);
    }

    private void updatePartyRecord(Map<String, Set<String>> record, Row row, Map<Integer, String> colIndexToHeader) {
        for (Map.Entry<Integer, String> entry : colIndexToHeader.entrySet()) {
            Cell cell = row.getCell(entry.getKey());
            String value = getCellValueAsString(cell);
            if (value.isEmpty()) continue;

            String field = mapToExpectedHeader(entry.getValue());
            if (field == null) continue;

            if ("Case No".equals(field)) {
                value = cleanCaseNo(value);
            } else if ("Stage".equals(field)) {
                value = cleanStageValue(value);
            }

            if (!value.isEmpty()) {
                record.get(field).add(value);
            }
        }
    }

    private String cleanCaseNo(String value) {
        if (value.startsWith("IFERROR") || value.startsWith("VLOOKUP") || value.contains("#REF!")) {
            return "";
        }
        return value.trim();
    }

    private String cleanStageValue(String value) {
        value = value.replaceAll("CLC\\s*\\(.*?\\)", "").trim();
        return value.replaceAll(",+", ",").replaceAll("^,|,$", "").trim();
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        try {
            DataFormatter formatter = new DataFormatter();
            return formatter.formatCellValue(cell).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private int getColumnIndex(Map<Integer, String> colIndexToHeader, String headerKey) {
        return colIndexToHeader.entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(headerKey))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(-1);
    }

    private String formatPartyCode(String value) {
        if (value == null) return "";
        return value.replaceAll("[^0-9]", "");
    }

    private int findHeaderRow(Sheet sheet) {
        for (Row row : sheet) {
            if (row == null) continue;
            int matchedHeaders = 0;
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING) {
                    String cellValue = cell.getStringCellValue().trim().toLowerCase();
                    if (cellValue.contains("party") || cellValue.contains("case") || 
                        cellValue.contains("court") || cellValue.contains("status")) {
                        matchedHeaders++;
                    }
                }
            }
            if (matchedHeaders >= 3) return row.getRowNum();
        }
        return -1;
    }

    private Map<Integer, String> mapColumnIndexes(Row headerRow) {
        Map<Integer, String> map = new HashMap<>();
        for (Cell cell : headerRow) {
            if (cell.getCellType() == CellType.STRING) {
                String header = cell.getStringCellValue().trim().toLowerCase();
                if (header.contains("party")) {
                    if (header.contains("code")) map.put(cell.getColumnIndex(), "partycode");
                    else if (header.contains("name")) map.put(cell.getColumnIndex(), "partyname");
                } 
                else if (header.contains("zone")) map.put(cell.getColumnIndex(), "zone");
                else if (header.contains("case")) {
                    if (header.contains("no") || header.contains("number")) map.put(cell.getColumnIndex(), "caseno");
                    else if (header.contains("lodged")) map.put(cell.getColumnIndex(), "caselodged");
                }
                else if (header.contains("court")) {
                    if (header.contains("name")) map.put(cell.getColumnIndex(), "courtname");
                    else if (header.contains("status")) map.put(cell.getColumnIndex(), "courtstatus");
                }
                else if (header.contains("stage") && header.contains("status")) {
                    map.put(cell.getColumnIndex(), "courtstatus");
                }
                else if (header.contains("previous") || header.contains("last") && header.contains("date")) {
                    map.put(cell.getColumnIndex(), "previousdate");
                }
                else if (header.contains("next") && header.contains("date")) {
                    map.put(cell.getColumnIndex(), "nextdate");
                }
            }
        }
        return map;
    }

    private String mapToExpectedHeader(String normalizedHeader) {
        if (normalizedHeader == null) return null;
        switch (normalizedHeader.toLowerCase()) {
            case "partycode": return "Party Code";
            case "partyname": return "Party Name";
            case "zone": return "Zone";
            case "caseno": return "Case No";
            case "caselodged": return "Court";
            case "courtstatus": return "Stage";
            case "previousdate": return "Last Date";
            case "nextdate": return "Next Date";
            default: return null;
        }
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }
}