package covert

import (
	"encoding/base64"
	"encoding/json"
	"strings"
)

// Служебный оп модели и курсорные оп — тот же каркас, что шлёт веб-редактор
// и OpenFlux (editorActivityLoop). Меняются только id участника и счётчик,
// текст документа эти оп не правят.
const (
	ooModelMetaOp  = "76;AgAAADEA//8BAJ+fl7jkAwIALQEAAAMAAAAAAAAAAAAAAAAAAAAAAAAA9v///xoAAAAyADAAMgA2AC4AMgAuADEALgAyADIANgA4AA=="
	ooEntityCursor = "35"
	ooCursorStream = "14"
)

func ooExcel(fileType string) bool {
	switch strings.ToLower(fileType) {
	case "xlsx", "xls", "xlsm", "csv":
		return true
	default:
		return false
	}
}

func ooSaveChanges(userID string, excel bool, seq int) []byte {
	short := userID
	if len(short) > 10 {
		short = short[:10]
	}
	changes, _ := json.Marshal([]string{
		ooModelMetaOp,
		ooCursorOp(short, seq),
		ooCursorOp(short, seq+1),
		ooCursorOp(short, seq+2),
	})
	info, _ := json.Marshal(map[string]string{
		"UserId":      userID,
		"UserShortId": short,
		"CursorInfo":  ooCursorInfo(short, seq),
	})
	body, _ := json.Marshal([]interface{}{"message", map[string]interface{}{
		"type":                "saveChanges",
		"changes":             string(changes),
		"startSaveChanges":    true,
		"endSaveChanges":      true,
		"isCoAuthoring":       true,
		"isExcel":             excel,
		"deleteIndex":         nil,
		"excelAdditionalInfo": string(info),
		"unlock":              false,
		"releaseLocks":        true,
	}})
	return append([]byte("42"), body...)
}

func ooCursorOp(userShortID string, seq int) string {
	payload := make([]byte, 0, 32)
	payload = append(payload, 0x06, 0x00, 0x00, 0x00)
	for _, r := range userShortID {
		payload = append(payload, byte(r), 0x00)
	}
	payload = append(payload,
		0x01, 0x00, 0x1c, 0x00,
		0x01, 0x00, 0x00, 0x00,
		byte(seq), 0x00, 0x00, 0x00,
		0x01, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
	)
	return ooEntityCursor + ";" + base64.StdEncoding.EncodeToString(payload)
}

func ooCursorInfo(userShortID string, seq int) string {
	payload := make([]byte, 0, 16)
	payload = append(payload, 0x06, 0x00, 0x00, 0x00)
	for _, r := range userShortID {
		payload = append(payload, byte(r), 0x00)
	}
	payload = append(payload, 0x03, 0x00, 0x00, 0x00, byte(seq), 0x00)
	return ooCursorStream + ";" + base64.StdEncoding.EncodeToString(payload)
}
