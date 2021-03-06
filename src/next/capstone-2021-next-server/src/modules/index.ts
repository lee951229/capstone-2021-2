import { combineReducers } from 'redux';
import { ThemeReducer, CategoryReducer, AppNavReducer, ContReducer, ContListReducer, UserListReducer } from "../reducers";

const reducer = combineReducers({
    ThemeReducer, CategoryReducer, AppNavReducer, ContReducer, ContListReducer, UserListReducer
});
export default reducer;
export type RootState = ReturnType<typeof reducer>