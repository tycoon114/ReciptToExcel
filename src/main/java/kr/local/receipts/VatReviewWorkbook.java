package kr.local.receipts;

import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

/** 엑셀의 검토 색상과 기준 안내를 공유합니다. 색상은 제안 상태이며 세액이나 집계에는 영향을 주지 않습니다. */
final class VatReviewWorkbook {
    static Map<String,CellStyle> styles(Workbook book) {
        Map<String,CellStyle> styles = new HashMap<>();
        Map<String,String[]> colors = Map.of("CANDIDATE",new String[]{"E3F3EA","14633D"},
                "NON_DEDUCTIBLE",new String[]{"FDE8E8","A52222"},"NO_VAT",new String[]{"EDF1F5","475569"},
                "REVIEW",new String[]{"FFF2CD","865500"},"EXCLUDED",new String[]{"EDF1F5","475569"});
        colors.forEach((code,pair)->{
            var style=(XSSFCellStyle)book.createCellStyle();
            style.setFillForegroundColor(new XSSFColor(java.awt.Color.decode("#"+pair[0]),null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);var font=book.createFont();font.setFontName("맑은 고딕");font.setBold(true);
            ((org.apache.poi.xssf.usermodel.XSSFFont)font).setColor(new XSSFColor(java.awt.Color.decode("#"+pair[1]),null));
            style.setFont(font);styles.put(code,style);
        });return styles;
    }
    static void guide(Workbook book) {
        var sheet=book.createSheet("부가세검토안내");
        String[][] rows = {
            {"부가세 매입세액 공제 검토", "규칙 버전: "+VatDeductionReview.VERSION},
            {"사용 범위", VatDeductionReview.NOTICE},
            {"공제 후보 · 초록", "자료상 일반과세자 또는 세금계산서 발급 간이과세자이며 세액·증빙 조건을 충족하는 후보. 실제 공제·환급 확정은 아닙니다."},
            {"불공제 검토 · 빨강", "접대비, 거래 당시 유형을 확인할 수 있는 세금계산서 미발급 간이과세자·면세사업자 등. 계정과목과 실제 거래를 최종 대조하세요."},
            {"세액 없음 · 회색", "부가세에 0원이 명시된 자료. 면세·영세 등의 구분과 사업상 비용 인정 여부는 별개입니다."},
            {"확인 필요 · 노랑", "정보 부족·과거 유형 불명·차량비·개인 지출 의심·교통/특수 업종·토지·면세사업 관련 비용·적격 증빙 미확인 등."},
            {"간이과세자", "간이과세는 면세·비과세와 다릅니다. 02와 07(세금계산서 발급사업자)을 구분합니다."},
            {"과거 거래", "전환일 미제공과 과거 날짜만으로 후보 검토를 차단하지 않습니다. 현재 조회 기준의 조건부 제안임을 사유에 표시합니다. 실제 전환일·세금계산서 적용일보다 이전이면 확인 필요입니다."},
            {"검토할 추가 정보", "매입자 회사의 과세·면세·겸영 구분, 실제 업무 목적, 차종·영업용 예외, 품목별 과세·면세, 증빙 진위·필수사항, 신고 반영 여부 등은 영수증만으로 모두 알 수 없습니다."},
            {"금액과 기장", "이 분류로 세액·원금·기장 방식·확정 합계를 자동 변경하지 않습니다. 부가세 불공제가 지출 전액의 비용 인정 불가를 뜻하지 않습니다."},
            {"엑셀 직접 수정", "분류는 내보내기 시점의 값입니다. 엑셀에서 계정과목·번호·세액을 바꿔도 자동 재판정하지 않습니다. 앱에서 수정·재출력하거나 세무사가 최종 판단하세요."},
            {"기장 전표", "기장 시 분류 사본을 보관합니다. 새 기능 이전 전표는 당시 사본이 없어 확인 필요로 표시합니다."},
            {"법령", "부가가치세법 제38·39·46조, 시행령 제88조 제5항 등. 규칙은 세무 신고 전체를 대체하지 않습니다."},
            {"부가가치세법", "https://www.law.go.kr/법령/부가가치세법"},
            {"카드전표 공급자 제한", "https://www.law.go.kr/lsLinkCommonInfo.do?lsJoLnkSeq=1032157243"},
            {"국세청 안내", "https://nts.go.kr/nts/cm/cntnts/cntntsView.do?cntntsId=7799&mi=2475"}
        };
        var wrap=book.createCellStyle();wrap.setWrapText(true);wrap.setVerticalAlignment(VerticalAlignment.TOP);
        for(int r=0;r<rows.length;r++){var row=sheet.createRow(r);row.setHeightInPoints(r==0?30:58);
            for(int c=0;c<2;c++){var cell=row.createCell(c);cell.setCellValue(rows[r][c]);cell.setCellStyle(wrap);}}
        sheet.setColumnWidth(0,30*256);sheet.setColumnWidth(1,112*256);sheet.createFreezePane(0,2);sheet.setDisplayGridlines(false);
    }
}
