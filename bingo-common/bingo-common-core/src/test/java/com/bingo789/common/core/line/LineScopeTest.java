package com.bingo789.common.core.line;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineScopeTest {

    @Test
    void parsesTheWireFormat() {
        LineScope scope = LineScope.parse(" 2,1 ,2");
        assertThat(scope.contains(1)).isTrue();
        assertThat(scope.contains(3)).isFalse();
        assertThat(scope.toCsv()).isEqualTo("1,2");
        assertThat(LineScope.parse(null).isAll()).isTrue();
        assertThat(LineScope.parse("*").contains(42)).isTrue();
    }

    @Test
    void intersectsMultiLineData() {
        assertThat(LineScope.of(1).intersects(List.of(1, 2))).isTrue();
        assertThat(LineScope.of(3).intersects(List.of(1, 2))).isFalse();
        assertThat(LineScope.all().intersects(List.of(5))).isTrue();
    }

    @Test
    void rejectsInvalidLines() {
        assertThatThrownBy(() -> LineScope.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineScope.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(UserLine.orDefault(null)).isEqualTo(UserLine.DEFAULT);
        assertThat(UserLine.orDefault(0)).isEqualTo(UserLine.DEFAULT);
    }
}
